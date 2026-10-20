package ru.spbsetu.moipis.config;

import ru.spbsetu.moipis.fsm.Graph;
import ru.spbsetu.moipis.fsm.Snapshot;
import ru.spbsetu.moipis.fsm.Transition;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** YAML fields. currentState is optional and used when restoring a saved snapshot. */
public record AutomatonConfig(
        String initialState,
        List<String> states,
        List<Transition> transitions,
        String currentState
) {
    public Snapshot toSnapshot() {
        if (states == null) {
            throw new IllegalArgumentException("states are required");
        }
        Set<String> uniqueStates = new HashSet<>(states);
        if (uniqueStates.size() != states.size()) {
            throw new IllegalArgumentException("Duplicate state");
        }
        Graph graph = new Graph(initialState, uniqueStates, transitions);
        String state = currentState == null ? initialState : currentState;
        return new Snapshot(graph, state);
    }

    public static AutomatonConfig fromSnapshot(Snapshot snapshot) {
        Graph graph = snapshot.graph();
        return new AutomatonConfig(graph.initialState(), graph.states().stream().sorted().toList(),
                graph.transitions(), snapshot.currentState());
    }
}
