package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record StateMachineSpec(
        Path source,
        Engine engine,
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

    /** Reserved trigger name for the statesmith engine's polled/do-event transitions. */
    public static final String TICK_EVENT = "tick";

    public enum Engine {
        BUILTIN, STATESMITH
    }

    /**
     * A leaf state has an empty {@code states} and a null {@code initial}. A composite (only
     * possible with the statesmith engine) has one or more children in {@code states}, and -
     * only when it is ever entered directly (the machine's {@code initial}, or a transition's
     * {@code to}) - a non-null {@code initial} naming one of those direct children.
     */
    public record State(String name, String description, List<State> states, String initial) {
        public boolean isComposite() {
            return !states.isEmpty();
        }
    }

    public record Event(String name, String description, List<InterfaceSpec.Parameter> parameters) {
    }

    public record Transition(String from, String event, String to, boolean guard, String description) {
    }

    public static StateMachineSpec from(Path source, Map<String, Object> yaml) {
        String contextName = source.toString();
        String engineValue = Values.optionalString(yaml, "engine", "builtin", contextName).toLowerCase();
        Engine engine = switch (engineValue) {
            case "builtin" -> Engine.BUILTIN;
            case "statesmith" -> Engine.STATESMITH;
            default -> throw new CGenException(contextName + ".engine must be 'builtin' or 'statesmith', got '" + engineValue + "'");
        };
        return engine == Engine.STATESMITH ? fromStatesmith(source, yaml, contextName) : fromBuiltin(source, yaml, contextName);
    }

    // ------------------------------------------------------------------------------------------
    // builtin engine - unchanged behavior; States built here are always plain leaves.
    // ------------------------------------------------------------------------------------------

    private static StateMachineSpec fromBuiltin(Path source, Map<String, Object> yaml, String contextName) {
        Values.onlyKeys(yaml, contextName, "kind", "engine", "name", "description", "header", "source", "includes",
                "context", "initial", "states", "events", "transitions");
        if (!Values.requiredString(yaml, "kind", contextName).equals("state-machine")) {
            throw new CGenException(contextName + ".kind must be state-machine");
        }
        Values.CommonFields common = Values.commonFields(yaml, contextName, "state machine");
        String name = common.name();

        List<State> states = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, "states", contextName)) {
            String itemContext = contextName + ".states";
            Values.onlyKeys(item, itemContext, "name", "description");
            String stateName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            states.add(new State(stateName, Values.optionalString(item, "description", "", itemContext), List.of(), null));
        }
        if (states.isEmpty()) {
            throw new CGenException(contextName + ".states needs at least one state");
        }
        Values.uniqueNames(states.stream().map(State::name).toList(), contextName + ".states");

        List<Event> events = parseEvents(yaml, contextName);

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

        return new StateMachineSpec(source, Engine.BUILTIN, name, common.description(), common.header(), common.sourceFile(),
                common.includes(), common.context(), List.copyOf(states), List.copyOf(events),
                List.copyOf(transitions), initial);
    }

    // ------------------------------------------------------------------------------------------
    // statesmith engine - hierarchical states, event: tick reserved for the polled do-event.
    // ------------------------------------------------------------------------------------------

    private static StateMachineSpec fromStatesmith(Path source, Map<String, Object> yaml, String contextName) {
        Values.onlyKeys(yaml, contextName, "kind", "engine", "name", "description", "header", "source", "includes",
                "context", "initial", "states", "events", "transitions");
        if (!Values.requiredString(yaml, "kind", contextName).equals("state-machine")) {
            throw new CGenException(contextName + ".kind must be state-machine");
        }
        Values.CommonFields common = Values.commonFields(yaml, contextName, "state machine");
        String name = common.name();

        List<State> states = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, "states", contextName)) {
            states.add(parseState(item, contextName + ".states"));
        }
        if (states.isEmpty()) {
            throw new CGenException(contextName + ".states needs at least one state");
        }
        Map<String, State> byName = new LinkedHashMap<>();
        flattenInto(states, byName, contextName);

        List<Event> events = parseEvents(yaml, contextName);
        if (events.stream().anyMatch(event -> event.name().equals(TICK_EVENT))) {
            throw new CGenException(contextName + ".events must not declare '" + TICK_EVENT
                    + "' - it is reserved for the polled do-event transition trigger (transitions: - { event: tick, ... })");
        }

        String initial = Values.identifier(Values.requiredString(yaml, "initial", contextName), contextName + ".initial");
        if (states.stream().noneMatch(state -> state.name().equals(initial))) {
            throw new CGenException(contextName + ".initial references unknown top-level state '" + initial + "'");
        }

        List<Transition> transitions = new ArrayList<>();
        List<Map<String, Object>> transitionItems = Values.mapList(yaml, "transitions", contextName);
        for (int index = 0; index < transitionItems.size(); index++) {
            Map<String, Object> item = transitionItems.get(index);
            String itemContext = contextName + ".transitions[" + index + "]";
            Values.onlyKeys(item, itemContext, "from", "event", "to", "guard", "description");
            String from = Values.identifier(Values.requiredString(item, "from", itemContext), itemContext + ".from");
            String to = Values.identifier(Values.requiredString(item, "to", itemContext), itemContext + ".to");
            if (!byName.containsKey(from)) {
                throw new CGenException(itemContext + ".from references unknown state '" + from + "'");
            }
            if (!byName.containsKey(to)) {
                throw new CGenException(itemContext + ".to references unknown state '" + to + "'");
            }
            String eventName = Values.identifier(Values.requiredString(item, "event", itemContext), itemContext + ".event");
            if (!eventName.equals(TICK_EVENT) && events.stream().noneMatch(event -> event.name().equals(eventName))) {
                throw new CGenException(itemContext + ".event references unknown event '" + eventName + "'");
            }
            boolean guard = Boolean.parseBoolean(Values.optionalString(item, "guard", "false", itemContext));
            transitions.add(new Transition(from, eventName, to, guard, Values.optionalString(item, "description", "", itemContext)));
        }
        List<String> transitionKeys = transitions.stream().map(transition -> transition.from() + "/" + transition.event()).toList();
        Values.uniqueNames(transitionKeys, contextName + ".transitions (from/event pairs)");

        // Every composite ever entered directly - the machine's initial, or a transition's to -
        // must resolve to a concrete leaf through its own (and its descendants') initial.
        Set<String> enteredDirectly = new LinkedHashSet<>();
        enteredDirectly.add(initial);
        for (Transition transition : transitions) {
            enteredDirectly.add(transition.to());
        }
        for (String stateName : enteredDirectly) {
            requireResolvableToLeaf(byName.get(stateName), byName, contextName);
        }

        return new StateMachineSpec(source, Engine.STATESMITH, name, common.description(), common.header(), common.sourceFile(),
                common.includes(), common.context(), List.copyOf(states), List.copyOf(events),
                List.copyOf(transitions), initial);
    }

    private static State parseState(Map<String, Object> item, String itemContext) {
        Values.onlyKeys(item, itemContext, "name", "description", "states", "initial");
        String stateName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
        List<State> children = new ArrayList<>();
        if (item.get("states") != null) {
            for (Map<String, Object> childItem : Values.mapList(item, "states", itemContext)) {
                children.add(parseState(childItem, itemContext + ".states"));
            }
            if (children.isEmpty()) {
                throw new CGenException(itemContext + ".states needs at least one state when present");
            }
        }
        String childInitial = Values.optionalString(item, "initial", null, itemContext);
        if (childInitial != null) {
            childInitial = Values.identifier(childInitial, itemContext + ".initial");
        }
        return new State(stateName, Values.optionalString(item, "description", "", itemContext), List.copyOf(children), childInitial);
    }

    private static void flattenInto(List<State> states, Map<String, State> out, String contextName) {
        for (State state : states) {
            if (out.put(state.name(), state) != null) {
                throw new CGenException(contextName + ".states contains duplicate name '" + state.name() + "'");
            }
            flattenInto(state.states(), out, contextName);
        }
    }

    private static void requireResolvableToLeaf(State state, Map<String, State> byName, String contextName) {
        State current = state;
        while (current.isComposite()) {
            State parent = current;
            if (parent.initial() == null) {
                throw new CGenException(contextName + ": composite state '" + parent.name()
                        + "' is entered directly (as the machine's initial or a transition's to) and must declare its own 'initial'");
            }
            current = parent.states().stream().filter(child -> child.name().equals(parent.initial())).findFirst()
                    .orElseThrow(() -> new CGenException(contextName + ": state '" + parent.name()
                            + "'.initial references '" + parent.initial() + "', which is not one of its direct children"));
        }
    }

    private static List<Event> parseEvents(Map<String, Object> yaml, String contextName) {
        List<Event> events = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, "events", contextName)) {
            String itemContext = contextName + ".events";
            Values.onlyKeys(item, itemContext, "name", "description", "parameters");
            String eventName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            List<InterfaceSpec.Parameter> parameters = InterfaceSpec.parseParameters(item, "parameters", itemContext, "event " + eventName);
            events.add(new Event(eventName, Values.optionalString(item, "description", "", itemContext), parameters));
        }
        Values.uniqueNames(events.stream().map(Event::name).toList(), contextName + ".events");
        return events;
    }

    // ------------------------------------------------------------------------------------------
    // Convenience accessors for the statesmith renderers - the whole hierarchy flattened.
    // ------------------------------------------------------------------------------------------

    /** Every state in the hierarchy, leaf and composite, depth-first, declaration order. */
    public List<State> allStatesFlat() {
        List<State> flat = new ArrayList<>();
        collectFlat(states, flat);
        return flat;
    }

    private static void collectFlat(List<State> level, List<State> out) {
        for (State state : level) {
            out.add(state);
            collectFlat(state.states(), out);
        }
    }

    /** Leaf states only, depth-first, declaration order - the public {@code <name>_state_t} shape. */
    public List<State> leafStates() {
        return allStatesFlat().stream().filter(state -> !state.isComposite()).toList();
    }

    /**
     * The concrete leaf {@link #initial()} resolves to, walking each composite's own
     * {@code initial} down until a leaf is reached. Only meaningful for the statesmith engine,
     * where {@link #requireResolvableToLeaf} guarantees this always terminates on a leaf.
     */
    public State effectiveInitialLeaf() {
        Map<String, State> byName = new LinkedHashMap<>();
        flattenInto(states, byName, source.toString());
        State current = byName.get(initial);
        while (current.isComposite()) {
            State parent = current;
            current = parent.states().stream().filter(child -> child.name().equals(parent.initial())).findFirst().orElseThrow();
        }
        return current;
    }
}
