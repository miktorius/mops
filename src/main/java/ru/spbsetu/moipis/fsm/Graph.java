package ru.spbsetu.moipis.fsm;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record Graph(String initialState, Set<String> states, List<Transition> transitions,
                    Set<String> events, Set<String> finalStates) {
    public Graph(String initialState, Set<String> states, List<Transition> transitions) {
        this(initialState, states, transitions, null, Set.of());
    }

    public Graph {
        requireId(initialState);
        if (states == null || transitions == null) {
            throw new IllegalArgumentException("states and transitions are required");
        }
        states = Set.copyOf(states);
        transitions = List.copyOf(transitions);
        if (events == null) {
            events = new HashSet<>();
            for (Transition transition : transitions) events.add(transition.event());
        }
        events = Set.copyOf(events);
        finalStates = finalStates == null ? Set.of() : Set.copyOf(finalStates);
        events.forEach(Graph::requireId);
        if (!states.containsAll(finalStates)) {
            throw new IllegalArgumentException("Final states must exist");
        }
        states.forEach(Graph::requireId);
        if (!states.contains(initialState)) {
            throw new IllegalArgumentException("Initial state must exist");
        }
        var keys = new HashSet<List<String>>();
        for (var transition : transitions) {
            if (!events.contains(transition.event())) {
                throw new IllegalArgumentException("Register the event before adding a transition");
            }
            if (finalStates.contains(transition.from())) {
                throw new IllegalArgumentException("Final states cannot have outgoing transitions");
            }
            if (!states.contains(transition.from()) || !states.contains(transition.to())) {
                throw new IllegalArgumentException("Transition references an unknown state");
            }
            if (!keys.add(List.of(transition.from(), transition.event()))) {
                throw new IllegalArgumentException("Only one transition per state and event is allowed");
            }
        }
    }

    public String nextState(String currentState, String event) {
        for (Transition transition : transitions) {
            if (transition.from().equals(currentState) && transition.event().equals(event)) {
                return transition.to();
            }
        }
        return currentState;
    }

    // Graph is immutable: each operation creates and validates a new graph.
    public Graph withState(String state) {
        requireId(state);
        Set<String> updatedStates = new HashSet<>(states);
        if (!updatedStates.add(state)) {
            throw new IllegalArgumentException("State already exists");
        }
        return new Graph(initialState, updatedStates, transitions, events, finalStates);
    }

    public Graph withoutState(String state) {
        requireId(state);
        if (state.equals(initialState)) {
            throw new IllegalArgumentException("Cannot remove initial state");
        }
        Set<String> updatedStates = new HashSet<>(states);
        if (!updatedStates.remove(state)) {
            throw new IllegalArgumentException("Unknown state");
        }
        List<Transition> updatedTransitions = new ArrayList<>(transitions);
        updatedTransitions.removeIf(t -> t.from().equals(state) || t.to().equals(state));
        Set<String> updatedFinalStates = new HashSet<>(finalStates);
        updatedFinalStates.remove(state);
        return new Graph(initialState, updatedStates, updatedTransitions, events, updatedFinalStates);
    }

    public Graph withTransition(Transition transition) {
        List<Transition> updatedTransitions = new ArrayList<>(transitions);
        updatedTransitions.add(transition);
        return new Graph(initialState, states, updatedTransitions, events, finalStates);
    }

    public Graph withoutTransition(String from, String event) {
        List<Transition> updatedTransitions = new ArrayList<>(transitions);
        if (!updatedTransitions.removeIf(t -> t.from().equals(from) && t.event().equals(event))) {
            throw new IllegalArgumentException("Unknown transition");
        }
        return new Graph(initialState, states, updatedTransitions, events, finalStates);
    }

    public Graph withEvent(String event) {
        requireId(event);
        Set<String> updatedEvents = new HashSet<>(events);
        if (!updatedEvents.add(event)) throw new IllegalArgumentException("Event already exists");
        return new Graph(initialState, states, transitions, updatedEvents, finalStates);
    }

    public Graph withInitialState(String state) {
        return new Graph(state, states, transitions, events, finalStates);
    }

    public Graph withoutEvent(String event) {
        requireId(event);
        Set<String> updatedEvents = new HashSet<>(events);
        if (!updatedEvents.remove(event)) {
            throw new IllegalArgumentException("Unknown event");
        }
        List<Transition> updatedTransitions = new ArrayList<>(transitions);
        updatedTransitions.removeIf(transition -> transition.event().equals(event));
        return new Graph(initialState, states, updatedTransitions, updatedEvents, finalStates);
    }

    public Graph withFinalState(String state, boolean terminal) {
        requireId(state);
        if (!states.contains(state)) throw new IllegalArgumentException("Unknown state");
        Set<String> updatedFinalStates = new HashSet<>(finalStates);
        if (terminal) updatedFinalStates.add(state);
        else updatedFinalStates.remove(state);
        return new Graph(initialState, states, transitions, events, updatedFinalStates);
    }

    public static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Identifier must not be blank");
        }
    }
}
