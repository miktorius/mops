package ru.spbsetu.moipis.fsm;

public record Transition(String from, String event, String to) {
    public Transition {
        Graph.requireId(from);
        Graph.requireId(event);
        Graph.requireId(to);
    }
}
