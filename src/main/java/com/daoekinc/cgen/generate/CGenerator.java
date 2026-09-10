package com.daoekinc.cgen.generate;

import com.daoekinc.cgen.CGenException;
import com.daoekinc.cgen.config.YamlFiles;
import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ModuleSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.project.ProjectService;
import com.daoekinc.cgen.tag.CGenTag;
import com.daoekinc.cgen.tag.TagHelper;
import com.daoekinc.cgen.tag.TagHelper.UserRegions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CGenerator {
    private final YamlFiles yamlFiles;
    private final TagHelper tags;
    private final ProjectService projects;

    public CGenerator(YamlFiles yamlFiles, TagHelper tags, ProjectService projects) {
        this.yamlFiles = yamlFiles;
        this.tags = tags;
        this.projects = projects;
    }

    public List<Path> generate(ProjectConfig project, Path scope) {
        DocumentationRenderer documentation = new DocumentationRenderer(project, yamlFiles);
        Map<String, InterfaceSpec> interfaces = loadInterfaces(project);
        List<ModulePlan> modules = new ArrayList<>();
        for (Path path : specificationFiles(scope, ".module.yaml", project)) {
            ModuleSpec module = ModuleSpec.from(path, yamlFiles.load(path));
            List<InterfaceSpec> implemented = new ArrayList<>();
            for (String name : module.implementsInterfaces()) {
                InterfaceSpec contract = interfaces.get(name);
                if (contract == null) {
                    throw new CGenException(path + " implements unknown interface '" + name + "'");
                }
                implemented.add(contract);
            }
            modules.add(new ModulePlan(module, List.copyOf(implemented)));
        }

        // Preflight every destination and render every file before changing the filesystem.
        List<Output> outputs = new ArrayList<>();
        Set<Path> destinations = new LinkedHashSet<>();
        for (InterfaceSpec spec : interfaces.values()) {
            if (!spec.source().startsWith(scope)) {
                continue;
            }
            Path output = spec.source().getParent().resolve(spec.header()).toAbsolutePath().normalize();
            requireUniqueDestination(destinations, output);
            UserRegions regions = tags.readForGeneration(output);
            outputs.add(new Output(output, renderInterface(project, spec, documentation, regions)));
        }
        for (ModulePlan plan : modules) {
            ModuleSpec module = plan.module();
            Path header = module.source().getParent().resolve(module.header());
            Path source = module.source().getParent().resolve(module.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header);
            UserRegions sourceRegions = tags.readForGeneration(source);
            removeGeneratedFunctionDefaults(project, interfaces.values(), sourceRegions);
            outputs.add(new Output(header, renderModuleHeader(project, module, plan.interfaces(), documentation, headerRegions)));
            outputs.add(new Output(source, renderModuleSource(project, module, plan.interfaces(), documentation, sourceRegions)));
        }
        outputs.forEach(output -> tags.writeGenerated(output.path(), output.content(), project.lineEnding()));
        return outputs.stream().map(Output::path).toList();
    }

    public List<Path> cleanTags(ProjectConfig project, Path scope) {
        List<Path> cleaned = new ArrayList<>();
        for (InterfaceSpec spec : loadInterfaces(project).values()) {
            if (spec.source().startsWith(scope)) {
                Path header = spec.source().getParent().resolve(spec.header());
                if (Files.isRegularFile(header) && tags.stripTags(header)) {
                    cleaned.add(header);
                }
            }
        }
        for (Path path : specificationFiles(scope, ".module.yaml", project)) {
            ModuleSpec module = ModuleSpec.from(path, yamlFiles.load(path));
            for (Path output : List.of(path.getParent().resolve(module.header()), path.getParent().resolve(module.sourceFile()))) {
                if (Files.isRegularFile(output) && tags.stripTags(output)) {
                    cleaned.add(output);
                }
            }
        }
        return List.copyOf(cleaned);
    }

    public DetachResult detach(ProjectConfig project) {
        Set<Path> configurationFiles = new LinkedHashSet<>();
        configurationFiles.addAll(specificationFiles(project.root(), ".interface.yaml", project));
        configurationFiles.addAll(specificationFiles(project.root(), ".module.yaml", project));
        if (project.documentation().customFile() != null) {
            configurationFiles.add(project.documentation().customFile());
        }
        configurationFiles.add(project.root().resolve(ProjectService.PROJECT_FILE));

        List<Path> cleaned = cleanTags(project, project.root());
        List<Path> deleted = new ArrayList<>();
        for (Path file : configurationFiles) {
            try {
                if (Files.deleteIfExists(file)) {
                    deleted.add(file);
                }
            } catch (IOException exception) {
                throw new CGenException("Cannot remove CGen configuration " + file + ": " + exception.getMessage(), exception);
            }
        }
        return new DetachResult(List.copyOf(cleaned), List.copyOf(deleted));
    }

    private Map<String, InterfaceSpec> loadInterfaces(ProjectConfig project) {
        Map<String, InterfaceSpec> result = new LinkedHashMap<>();
        for (Path path : specificationFiles(project.root(), ".interface.yaml", project)) {
            InterfaceSpec spec = InterfaceSpec.from(path, yamlFiles.load(path));
            InterfaceSpec previous = result.putIfAbsent(spec.name(), spec);
            if (previous != null) {
                throw new CGenException("Duplicate interface name '" + spec.name() + "' in " + previous.source() + " and " + path);
            }
        }
        return result;
    }

    private List<Path> specificationFiles(Path directory, String suffix, ProjectConfig project) {
        projects.existingDirectory(project, directory);
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !isExcludedProjectPath(project.root(), path))
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new CGenException("Cannot scan " + directory + ": " + exception.getMessage(), exception);
        }
    }

    private static boolean isExcludedProjectPath(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0) {
            return false;
        }
        String first = relative.getName(0).toString();
        return first.equals(".git") || first.equals("target");
    }

    private String renderInterface(ProjectConfig project, InterfaceSpec spec, DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("interface", spec.source().getFileName().toString())).append('\n');
        out.append(docs.file(spec.header(), spec.description())).append('\n');
        String guard = macro(spec.header());
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        appendIncludes(out, List.of("<stddef.h>"), spec.includes());
        out.append(user.render("interface.preamble", "")).append('\n');

        for (InterfaceSpec.EnumDef type : spec.enums()) {
            out.append(CGenTag.generatedItem("enum", type.name())).append('\n');
            out.append(docs.type(type.name(), type.description()));
            out.append("typedef enum\n{\n");
            for (int index = 0; index < type.values().size(); index++) {
                InterfaceSpec.EnumValue value = type.values().get(index);
                out.append(indent(project, 1)).append(value.name());
                if (value.value() != null) {
                    out.append(" = ").append(value.value());
                }
                if (index + 1 < type.values().size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            out.append("} ").append(type.name()).append(";\n\n");
        }
        for (InterfaceSpec.StructDef type : spec.structs()) {
            out.append(CGenTag.generatedItem("struct", type.name())).append('\n');
            out.append(docs.type(type.name(), type.description()));
            out.append("typedef struct\n{\n");
            for (InterfaceSpec.Field field : type.fields()) {
                out.append(indent(project, 1));
                appendTypedName(out, field.type(), field.name());
                out.append(";\n");
            }
            out.append("} ").append(type.name()).append(";\n\n");
        }
        out.append(user.render("interface.declarations", "")).append('\n');

        out.append(CGenTag.generatedItem("interface-table", spec.name())).append('\n');
        out.append("typedef struct\n{\n").append(indent(project, 1)).append("void *context;\n");
        for (InterfaceSpec.Function function : spec.functions()) {
            out.append(indent(project, 1)).append(function.returnType()).append(" (*").append(function.name())
                    .append(")(void *context");
            appendParameters(out, function.parameters(), true);
            out.append(");\n");
        }
        out.append("} ").append(spec.name()).append("_interface_t;\n\n");

        for (InterfaceSpec.Function function : spec.functions()) {
            out.append(CGenTag.generatedItem("function", function.name())).append('\n');
            out.append(docs.function(spec.name() + "_" + function.name(), function.description(), function.returnType(), function.parameters()));
            out.append("static inline ").append(function.returnType()).append(' ').append(spec.name()).append('_')
                    .append(function.name()).append('(').append(spec.name()).append("_interface_t *interface");
            appendParameters(out, function.parameters(), true);
            out.append(")\n{\n");
            out.append(indent(project, 1)).append("if (interface == NULL)\n").append(indent(project, 1)).append("{\n")
                    .append(indent(project, 2)).append(errorReturn(function.returnType(), function.invalidReturn())).append('\n')
                    .append(indent(project, 1)).append("}\n\n");
            out.append(indent(project, 1)).append("if ((interface->context == NULL) || (interface->")
                    .append(function.name()).append(" == NULL))\n").append(indent(project, 1)).append("{\n")
                    .append(indent(project, 2)).append(errorReturn(function.returnType(), function.uninitializedReturn())).append('\n')
                    .append(indent(project, 1)).append("}\n\n");
            out.append(indent(project, 1));
            if (!function.returnType().equals("void")) {
                out.append("return ");
            }
            out.append("interface->").append(function.name()).append("(interface->context");
            for (InterfaceSpec.Parameter parameter : function.parameters()) {
                out.append(", ").append(parameter.name());
            }
            out.append(");\n}\n\n");
        }
        out.append(user.render("interface.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    private String renderModuleHeader(ProjectConfig project, ModuleSpec module, List<InterfaceSpec> interfaces,
                                      DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("module-header", module.source().getFileName().toString())).append('\n');
        out.append(docs.file(module.header(), module.description())).append('\n');
        String guard = macro(module.header());
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        List<String> contractIncludes = interfaces.stream()
                .map(spec -> quotedRelative(module.source().getParent(), spec.source().getParent().resolve(spec.header())))
                .toList();
        appendIncludes(out, contractIncludes, module.includes());
        out.append(user.render("module.header.preamble", "")).append('\n');

        for (ModuleSpec.Variable variable : module.variables()) {
            if (variable.visibility() == ModuleSpec.Visibility.PUBLIC) {
                out.append(CGenTag.generatedItem("public-variable", variable.name())).append('\n');
                out.append(docs.variable(variable.name(), variable.description()));
                out.append("extern ");
                appendTypedName(out, variable.type(), variable.name());
                out.append(";\n\n");
            }
        }
        out.append(CGenTag.generatedItem("context", module.name())).append('\n');
        out.append("typedef struct\n{\n");
        if (module.context().isEmpty()) {
            out.append(indent(project, 1)).append("unsigned char reserved;\n");
        } else {
            for (InterfaceSpec.Field field : module.context()) {
                out.append(indent(project, 1));
                appendTypedName(out, field.type(), field.name());
                out.append(";\n");
            }
        }
        out.append("} ").append(module.name()).append("_context_t;\n\n");

        for (InterfaceSpec contract : interfaces) {
            String function = module.name() + "_bind_" + contract.name();
            out.append(CGenTag.generatedItem("bind-function", function)).append('\n');
            out.append("void ").append(function).append('(').append(contract.name()).append("_interface_t *interface, ")
                    .append(module.name()).append("_context_t *context);\n\n");
        }
        out.append(user.render("module.header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    private String renderModuleSource(ProjectConfig project, ModuleSpec module, List<InterfaceSpec> interfaces,
                                      DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("module-source", module.source().getFileName().toString())).append('\n');
        out.append(docs.file(module.sourceFile(), module.description())).append('\n');
        out.append("#include \"").append(module.header()).append("\"\n\n");
        out.append(user.render("module.source.includes", "")).append('\n');
        for (ModuleSpec.Variable variable : module.variables()) {
            out.append(CGenTag.generatedItem(variable.visibility() == ModuleSpec.Visibility.PRIVATE ? "private-variable" : "variable-definition", variable.name())).append('\n');
            out.append(docs.variable(variable.name(), variable.description()));
            if (variable.visibility() == ModuleSpec.Visibility.PRIVATE) {
                out.append("static ");
            }
            appendTypedName(out, variable.type(), variable.name());
            if (variable.initial() != null) {
                out.append(" = ").append(variable.initial());
            }
            out.append(";\n\n");
        }

        for (InterfaceSpec contract : interfaces) {
            for (InterfaceSpec.Function function : contract.functions()) {
                String implementation = module.name() + "_" + contract.name() + "_" + function.name();
                out.append(CGenTag.generatedItem("private-function", implementation)).append('\n');
                out.append("static ").append(function.returnType()).append(' ').append(implementation).append("(void *context");
                appendParameters(out, function.parameters(), true);
                out.append(")\n{\n");
                out.append(indent(project, 1)).append(module.name()).append("_context_t *module = (")
                        .append(module.name()).append("_context_t *)context;\n")
                        .append(indent(project, 1)).append("(void)module;\n\n");
                out.append(user.render("function." + contract.name() + "." + function.name() + ".body", ""));
                if (!function.returnType().equals("void")) {
                    out.append(indent(project, 1)).append("return ").append(function.invalidReturn()).append(";\n");
                }
                out.append("}\n\n");
            }
            String bind = module.name() + "_bind_" + contract.name();
            out.append(CGenTag.generatedItem("bind-function", bind)).append('\n');
            out.append("void ").append(bind).append('(').append(contract.name()).append("_interface_t *interface, ")
                    .append(module.name()).append("_context_t *context)\n{\n")
                    .append(indent(project, 1)).append("if (interface == NULL)\n")
                    .append(indent(project, 1)).append("{\n").append(indent(project, 2)).append("return;\n")
                    .append(indent(project, 1)).append("}\n\n")
                    .append(indent(project, 1)).append("interface->context = context;\n");
            for (InterfaceSpec.Function function : contract.functions()) {
                out.append(indent(project, 1)).append("interface->").append(function.name()).append(" = ")
                        .append(module.name()).append('_').append(contract.name()).append('_').append(function.name()).append(";\n");
            }
            out.append("}\n\n");
        }
        out.append(user.render("module.source.footer", ""));
        out.append(user.renderOrphans());
        return out.toString();
    }

    @SafeVarargs
    private static void appendIncludes(StringBuilder out, List<String>... groups) {
        Set<String> includes = new LinkedHashSet<>();
        for (List<String> group : groups) {
            includes.addAll(group);
        }
        for (String include : includes) {
            out.append("#include ").append(include).append('\n');
        }
        if (!includes.isEmpty()) {
            out.append('\n');
        }
    }

    private static void appendParameters(StringBuilder out, List<InterfaceSpec.Parameter> parameters, boolean leadingComma) {
        boolean comma = leadingComma;
        for (InterfaceSpec.Parameter parameter : parameters) {
            if (comma) {
                out.append(", ");
            }
            appendTypedName(out, parameter.type(), parameter.name());
            comma = true;
        }
    }

    private static void appendTypedName(StringBuilder out, String type, String name) {
        out.append(type);
        if (!type.stripTrailing().endsWith("*")) {
            out.append(' ');
        }
        out.append(name);
    }

    private static void requireUniqueDestination(Set<Path> destinations, Path path) {
        if (!destinations.add(path)) {
            throw new CGenException("Multiple YAML specifications generate " + path);
        }
    }

    private static void removeGeneratedFunctionDefaults(ProjectConfig project, Iterable<InterfaceSpec> interfaces,
                                                        UserRegions regions) {
        for (InterfaceSpec contract : interfaces) {
            for (InterfaceSpec.Function function : contract.functions()) {
                if (!function.returnType().equals("void")) {
                    String name = "function." + contract.name() + "." + function.name() + ".body";
                    String oldDefault = indent(project, 1) + "return " + function.invalidReturn() + ";";
                    regions.removeIfMatches(name, oldDefault);
                }
            }
        }
    }

    private static String errorReturn(String returnType, String value) {
        return returnType.equals("void") ? "return;" : "return " + value + ";";
    }

    private static String indent(ProjectConfig project, int level) {
        return " ".repeat(project.indent() * level);
    }

    private static String macro(String file) {
        String value = file.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return value.endsWith("_") ? value : value + "_";
    }

    private static String quotedRelative(Path fromDirectory, Path file) {
        return "\"" + fromDirectory.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/') + "\"";
    }

    private record ModulePlan(ModuleSpec module, List<InterfaceSpec> interfaces) {
    }

    public record DetachResult(List<Path> cleanedFiles, List<Path> deletedConfigurationFiles) {
    }

    private record Output(Path path, String content) {
    }
}
