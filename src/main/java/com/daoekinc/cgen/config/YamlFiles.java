package com.daoekinc.cgen.config;

import com.daoekinc.cgen.CGenException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlFiles {
    private final Yaml yaml;

    public YamlFiles() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(1_000_000);
        yaml = new Yaml(new SafeConstructor(options));
    }

    public Map<String, Object> load(Path path) {
        try {
            Object value = yaml.load(Files.readString(path));
            if (!(value instanceof Map<?, ?> raw)) {
                throw new CGenException(path + " must contain a YAML mapping at its root");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new CGenException(path + " contains a non-text key");
                }
                result.put(key, entry.getValue());
            }
            return result;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof CGenException cgenException) {
                throw cgenException;
            }
            throw new CGenException("Cannot read YAML " + path + ": " + exception.getMessage(), exception);
        }
    }
}
