package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record StateMachineSpec(
        Path source,
        String name,
        String description,
        String header,
        String sourceFile,
        List<String> includes,
        List<InterfaceSpec.Field> context,
        List<State> states,
        List<Event> events,
        List<Transition> transitions,
        String initial) {

    public record State(String name, String description) {
    }

    public record Event(String name, String description, List<InterfaceSpec.Parameter> parameters) {
    }

    public record Transition(String from, String event, String to, boolean guard, String description) {
    }

    public static StateMachineSpec from(Path source, Map<String, Object> yaml) {
        String contextName = source.toString();
        Values.onlyKeys(yaml, contextName, "kind", "name", "description", "header", "source", "includes",
                "context", "initial", "states", "events", "transitions");
        if (!Values.requiredString(yaml, "kind", contextName).equals("state-machine")) {
            throw new CGenException(contextName + ".kind must be state-machine");
        }
        String name = Values.identifier(Values.requiredString(yaml, "name", contextName), contextName + ".name");
        String description = Values.optionalString(yaml, "description", name + " state machine", contextName);
        String header = Values.outputFile(Values.optionalString(yaml, "header", name + ".h", contextName), ".h",
                contextName + ".header");
        String sourceFile = Values.outputFile(Values.optionalString(yaml, "source", name + ".c", contextName), ".c",
                contextName + ".source");
        List<String> includes = Values.stringList(yaml, "includes", contextName);
        List<InterfaceSpec.Field> context = InterfaceSpec.parseFields(yaml, "context", contextName);

        List<State> states = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, "states", contextName)) {
            String itemContext = contextName + ".states";
            Values.onlyKeys(item, itemContext, "name", "description");
            String stateName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            states.add(new State(stateName, Values.optionalString(item, "description", "", itemContext)));
        }
        if (states.isEmpty()) {
            throw new CGenException(contextName + ".states needs at least one state");
        }
        Values.uniqueNames(states.stream().map(State::name).toList(), contextName + ".states");

        List<Event> events = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, "events", contextName)) {
            String itemContext = contextName + ".events";
            Values.onlyKeys(item, itemContext, "name", "description", "parameters");
            String eventName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            List<InterfaceSpec.Parameter> parameters = new ArrayList<>();
            List<Map<String, Object>> parameterItems = Values.itemList(item, "parameters", itemContext,
                    """
                    parameters:
                      - uint8_t *buffer
                      - uint32_t len""", text -> Values.compactField(text, itemContext + ".parameters"));
            for (int parameterIndex = 0; parameterIndex < parameterItems.size(); parameterIndex++) {
                Map<String, Object> parameter = parameterItems.get(parameterIndex);
                String parameterContext = itemContext + ".parameters[" + parameterIndex + "]";
                Values.onlyKeys(parameter, parameterContext, "type", "name", "description");
                parameters.add(new InterfaceSpec.Parameter(
                        InterfaceSpec.oneLine(Values.requiredString(parameter, "type", parameterContext), parameterContext + ".type"),
                        Values.identifier(Values.requiredString(parameter, "name", parameterContext), parameterContext + ".name"),
                        Values.optionalString(parameter, "description", "", parameterContext)));
            }
            Values.uniqueNames(parameters.stream().map(InterfaceSpec.Parameter::name).toList(), itemContext + " event " + eventName);
            events.add(new Event(eventName, Values.optionalString(item, "description", "", itemContext), List.copyOf(parameters)));
        }
        Values.uniqueNames(events.stream().map(Event::name).toList(), contextName + ".events");

        String initial = Values.identifier(Values.requiredString(yaml, "initial", contextName), contextName + ".initial");
        if (states.stream().noneMatch(state -> state.name().equals(initial))) {
            throw new CGenException(contextName + ".initial references unknown state '" + initial + "'");
        }

        List<Transition> transitions = new ArrayList<>();
        List<Map<String, Object>> transitionItems = Values.mapList(yaml, "transitions", contextName);
        for (int index = 0; index < transitionItems.size(); index++) {
            Map<String, Object> item = transitionItems.get(index);
            String itemContext = contextName + ".transitions[" + index + "]";
            Values.onlyKeys(item, itemContext, "from", "event", "to", "guard", "description");
            String from = Values.identifier(Values.requiredString(item, "from", itemContext), itemContext + ".from");
            String to = Values.identifier(Values.requiredString(item, "to", itemContext), itemContext + ".to");
            if (states.stream().noneMatch(state -> state.name().equals(from))) {
                throw new CGenException(itemContext + ".from references unknown state '" + from + "'");
            }
            if (states.stream().noneMatch(state -> state.name().equals(to))) {
                throw new CGenException(itemContext + ".to references unknown state '" + to + "'");
            }
            String eventName = Values.identifier(Values.requiredString(item, "event", itemContext), itemContext + ".event");
            if (events.stream().noneMatch(event -> event.name().equals(eventName))) {
                throw new CGenException(itemContext + ".event references unknown event '" + eventName + "'");
            }
            boolean guard = Boolean.parseBoolean(Values.optionalString(item, "guard", "false", itemContext));
            transitions.add(new Transition(from, eventName, to, guard, Values.optionalString(item, "description", "", itemContext)));
        }
        List<String> transitionKeys = transitions.stream().map(transition -> transition.from() + "/" + transition.event()).toList();
        Values.uniqueNames(transitionKeys, contextName + ".transitions (from/event pairs)");

        return new StateMachineSpec(source, name, description, header, sourceFile, includes, context,
                List.copyOf(states), List.copyOf(events), List.copyOf(transitions), initial);
    }
}
