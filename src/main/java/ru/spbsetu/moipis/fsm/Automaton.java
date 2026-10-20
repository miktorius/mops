package ru.spbsetu.moipis.fsm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public final class Automaton implements AutoCloseable {
    private final SnapshotStore store;
    private final Map<String, Consumer<Message>> handlers = new HashMap<>();
    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private Snapshot snapshot;
    private volatile boolean closed;

    public Automaton(SnapshotStore store, Graph initialGraph) {
        this.store = store;
        snapshot = store.load().orElseGet(() -> {
            var initial = new Snapshot(initialGraph, initialGraph.initialState());
            store.save(initial);
            return initial;
        });
        worker = Thread.ofPlatform().name("automaton-worker").daemon().start(this::run);
    }

    public synchronized Snapshot snapshot() {
        return snapshot;
    }

    public synchronized void registerHandler(String event, Consumer<Message> handler) {
        Graph.requireId(event);
        if (handler == null) throw new IllegalArgumentException("Handler is required");
        handlers.put(event, handler);
    }

    public CompletableFuture<Snapshot> submit(Message message) {
        if (message == null) throw new IllegalArgumentException("Message is required");
        synchronized (queue) {
            if (closed) throw new IllegalStateException("Automaton is closed");
            var pending = new Pending(message, new CompletableFuture<>());
            queue.add(pending);
            return pending.result();
        }
    }

    public synchronized void replaceGraph(Graph graph) {
        // Configuration updates never reset the running instance.
        persist(new Snapshot(graph, snapshot.currentState()));
    }

    public synchronized void addState(String state) {
        Graph.requireId(state);
        var states = new HashSet<>(snapshot.graph().states());
        if (!states.add(state)) throw new IllegalArgumentException("State already exists");
        replaceGraph(new Graph(snapshot.graph().initialState(), states, snapshot.graph().transitions()));
    }

    public synchronized void removeState(String state) {
        var graph = snapshot.graph();
        if (state.equals(snapshot.currentState()) || state.equals(graph.initialState())) {
            throw new IllegalArgumentException("Cannot remove current or initial state");
        }
        var states = new HashSet<>(graph.states());
        if (!states.remove(state)) throw new IllegalArgumentException("Unknown state");
        var transitions = graph.transitions().stream()
                .filter(t -> !t.from().equals(state) && !t.to().equals(state)).toList();
        replaceGraph(new Graph(graph.initialState(), states, transitions));
    }

    public synchronized void addTransition(Transition transition) {
        var transitions = new ArrayList<>(snapshot.graph().transitions());
        transitions.add(transition);
        replaceGraph(new Graph(snapshot.graph().initialState(), snapshot.graph().states(), transitions));
    }

    public synchronized void removeTransition(String from, String event) {
        var transitions = new ArrayList<>(snapshot.graph().transitions());
        if (!transitions.removeIf(t -> t.from().equals(from) && t.event().equals(event))) {
            throw new IllegalArgumentException("Unknown transition");
        }
        replaceGraph(new Graph(snapshot.graph().initialState(), snapshot.graph().states(), transitions));
    }

    private synchronized Snapshot process(Message message) {
        if (closed) throw new IllegalStateException("Automaton is closed");
        var handler = handlers.get(message.event());
        if (handler == null) throw new IllegalArgumentException("Unknown event: " + message.event());
        var next = snapshot.graph().transitions().stream()
                .filter(t -> t.from().equals(snapshot.currentState()) && t.event().equals(message.event()))
                .map(Transition::to).findFirst().orElse(snapshot.currentState());
        handler.accept(message);
        persist(new Snapshot(snapshot.graph(), next));
        return snapshot;
    }

    private void persist(Snapshot updated) {
        store.save(updated);
        snapshot = updated;
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var pending = queue.take();
                try {
                    var result = process(pending.message());
                    pending.result().complete(result);
                } catch (Exception exception) {
                    pending.result().completeExceptionally(exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public synchronized void close() {
        synchronized (queue) {
            closed = true;
        }
        worker.interrupt();
        Pending pending;
        while ((pending = queue.poll()) != null) {
            pending.result().completeExceptionally(new IllegalStateException("Automaton is closed"));
        }
    }

    private record Pending(Message message, CompletableFuture<Snapshot> result) {}
}
