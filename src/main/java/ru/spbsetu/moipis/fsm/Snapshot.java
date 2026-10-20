package ru.spbsetu.moipis.fsm;

public record Snapshot(Graph graph, String currentState) {
    public Snapshot {
        if (graph == null || !graph.states().contains(currentState)) {
            throw new IllegalArgumentException("Current state must exist");
        }
    }
}
