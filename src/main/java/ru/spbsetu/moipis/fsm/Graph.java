package ru.spbsetu.moipis.fsm;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record Graph(String initialState, Set<String> states, List<Transition> transitions) {
    public Graph {
        requireId(initialState);
        if (states == null || transitions == null) {
            throw new IllegalArgumentException("states and transitions are required");
        }
        states = Set.copyOf(states);
        transitions = List.copyOf(transitions);
        states.forEach(Graph::requireId);
        if (!states.contains(initialState)) {
            throw new IllegalArgumentException("Initial state must exist");
        }
        var keys = new HashSet<List<String>>();
        for (var transition : transitions) {
            if (!states.contains(transition.from()) || !states.contains(transition.to())) {
                throw new IllegalArgumentException("Transition references an unknown state");
            }
            if (!keys.add(List.of(transition.from(), transition.event()))) {
                throw new IllegalArgumentException("Only one transition per state and event is allowed");
            }
        }
    }

    public static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Identifier must not be blank");
        }
    }
}
