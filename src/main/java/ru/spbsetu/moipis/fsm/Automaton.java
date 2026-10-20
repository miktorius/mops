package ru.spbsetu.moipis.fsm;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public final class Automaton implements AutoCloseable {
    private final SnapshotStore store;
    private final Map<String, Consumer<Message>> handlers = new HashMap<>();
    private final LinkedBlockingQueue<QueuedMessage> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private Snapshot currentSnapshot;
    private volatile boolean closed;

    public Automaton(SnapshotStore store, Graph initialGraph) {
        this.store = store;
        currentSnapshot = store.load().orElseGet(() -> {
            Snapshot initial = new Snapshot(initialGraph, initialGraph.initialState());
            store.save(initial);
            return initial;
        });
        worker = Thread.ofPlatform().name("automaton-worker").daemon().start(this::run);
    }

    public synchronized Snapshot snapshot() {
        return currentSnapshot;
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
            QueuedMessage pending = new QueuedMessage(message, new CompletableFuture<>());
            queue.add(pending);
            return pending.result();
        }
    }

    public synchronized void replaceGraph(Graph graph) {
        // Configuration updates never reset the running instance.
        saveSnapshot(new Snapshot(graph, currentSnapshot.currentState()));
    }

    public synchronized void addState(String state) {
        replaceGraph(currentSnapshot.graph().withState(state));
    }

    public synchronized void removeState(String state) {
        if (currentSnapshot.currentState().equals(state)) {
            throw new IllegalArgumentException("Cannot remove current state");
        }
        replaceGraph(currentSnapshot.graph().withoutState(state));
    }

    public synchronized void addTransition(Transition transition) {
        replaceGraph(currentSnapshot.graph().withTransition(transition));
    }

    public synchronized void removeTransition(String from, String event) {
        replaceGraph(currentSnapshot.graph().withoutTransition(from, event));
    }

    // The same monitor protects graph updates and message execution.
    private synchronized Snapshot process(Message message) {
        if (closed) throw new IllegalStateException("Automaton is closed");
        Consumer<Message> handler = handlers.get(message.event());
        if (handler == null) throw new IllegalArgumentException("Unknown event: " + message.event());

        Graph graph = currentSnapshot.graph();
        String nextState = graph.nextState(currentSnapshot.currentState(), message.event());
        handler.accept(message);
        saveSnapshot(new Snapshot(graph, nextState));
        return currentSnapshot;
    }

    private void saveSnapshot(Snapshot updated) {
        // Publish in memory only after the database has accepted the update.
        store.save(updated);
        currentSnapshot = updated;
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                QueuedMessage pending = queue.take();
                try {
                    Snapshot result = process(pending.message());
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
        QueuedMessage pending;
        while ((pending = queue.poll()) != null) {
            pending.result().completeExceptionally(new IllegalStateException("Automaton is closed"));
        }
    }

    private record QueuedMessage(Message message, CompletableFuture<Snapshot> result) {}
}
