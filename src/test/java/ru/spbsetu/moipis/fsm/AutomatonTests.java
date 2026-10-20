package ru.spbsetu.moipis.fsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class AutomatonTests {
    private final Graph graph = new Graph("draft", Set.of("draft", "review", "approved"), List.of(
            new Transition("draft", "submit", "review"),
            new Transition("review", "reject", "draft"),
            new Transition("review", "approve", "approved")));

    @Test
    void followsLifecycleIncludingReturnAndRestoresState() {
        var store = new MemoryStore();
        try (var machine = new Automaton(store, graph)) {
            for (var event : List.of("submit", "reject", "approve")) {
                machine.registerHandler(event, message -> {});
            }
            assertEquals("review", send(machine, "submit").currentState());
            assertEquals("draft", send(machine, "reject").currentState());
            assertEquals("review", send(machine, "submit").currentState());
            assertEquals("approved", send(machine, "approve").currentState());
        }
        try (var restored = new Automaton(store, graph)) {
            assertEquals("approved", restored.snapshot().currentState());
        }
    }

    @Test
    void processesMessagesInFifoOrder() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var values = new ArrayList<Integer>();
        try (var machine = new Automaton(new MemoryStore(), graph)) {
            machine.registerHandler("record", message -> {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                values.add((Integer) message.payload().get("number"));
            });
            var first = machine.submit(new Message("record", Map.of("number", 1)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = machine.submit(new Message("record", Map.of("number", 2)));
            var third = machine.submit(new Message("record", Map.of("number", 3)));
            assertFalse(second.isDone());
            assertFalse(third.isDone());
            release.countDown();
            first.join();
            second.join();
            third.join();
            assertEquals(List.of(1, 2, 3), values);
            assertEquals("draft", machine.snapshot().currentState());
        } finally {
            release.countDown();
        }
    }

    @Test
    void addsAndRemovesStatesTransitionsAndHandlersAtRuntime() {
        try (var machine = new Automaton(new MemoryStore(), graph)) {
            machine.addState("archived");
            machine.addTransition(new Transition("draft", "archive", "archived"));
            machine.registerHandler("archive", message -> {});
            assertEquals("archived", send(machine, "archive").currentState());
            assertThrows(IllegalArgumentException.class, () -> machine.removeState("archived"));
            machine.addTransition(new Transition("archived", "restore", "draft"));
            machine.registerHandler("restore", message -> {});
            send(machine, "restore");
            machine.removeTransition("archived", "restore");
            machine.removeState("archived");
            assertFalse(machine.snapshot().graph().states().contains("archived"));
            assertTrue(machine.snapshot().graph().transitions().stream()
                    .noneMatch(t -> t.to().equals("archived") || t.from().equals("archived")));
        }
    }

    @Test
    void failuresKeepStateAndWorkerContinues() {
        try (var machine = new Automaton(new MemoryStore(), graph)) {
            assertThrows(CompletionException.class, () -> send(machine, "unknown"));
            machine.registerHandler("submit", message -> { throw new IllegalStateException("Failed"); });
            assertThrows(CompletionException.class, () -> send(machine, "submit"));
            assertEquals("draft", machine.snapshot().currentState());
            machine.registerHandler("submit", message -> {});
            assertEquals("review", send(machine, "submit").currentState());
        }
    }

    @Test
    void rejectsInvalidGraphChangesWithoutChangingSavedGraph() {
        var store = new MemoryStore();
        try (var machine = new Automaton(store, graph)) {
            var original = machine.snapshot();
            assertThrows(IllegalArgumentException.class, () -> machine.addState("draft"));
            assertThrows(IllegalArgumentException.class, () -> machine.removeState("draft"));
            assertThrows(IllegalArgumentException.class,
                    () -> machine.addTransition(new Transition("draft", "submit", "approved")));
            assertThrows(IllegalArgumentException.class,
                    () -> machine.addTransition(new Transition("draft", "other", "missing")));
            assertThrows(IllegalArgumentException.class,
                    () -> machine.replaceGraph(new Graph("other", Set.of("other"), List.of())));
            assertEquals(original, machine.snapshot());
            assertEquals(original, store.saved);
        }
    }

    @Test
    void storageFailureDoesNotPublishNewStateOrGraph() {
        var store = new MemoryStore();
        try (var machine = new Automaton(store, graph)) {
            machine.registerHandler("submit", message -> {});
            store.fail = true;
            assertThrows(CompletionException.class, () -> send(machine, "submit"));
            assertThrows(IllegalStateException.class, () -> machine.addState("extra"));
            assertEquals("draft", machine.snapshot().currentState());
            assertEquals(graph, machine.snapshot().graph());
            assertEquals(machine.snapshot(), store.saved);
        }
    }

    @Test
    void closedMachineRejectsMessages() {
        var machine = new Automaton(new MemoryStore(), graph);
        machine.close();
        assertThrows(IllegalStateException.class, () -> machine.submit(new Message("submit", Map.of())));
    }

    private Snapshot send(Automaton machine, String event) {
        return machine.submit(new Message(event, Map.of())).join();
    }

    private static class MemoryStore implements SnapshotStore {
        private Snapshot saved;
        private boolean fail;

        public Optional<Snapshot> load() { return Optional.ofNullable(saved); }

        public void save(Snapshot snapshot) {
            if (fail) throw new IllegalStateException("Database unavailable");
            saved = snapshot;
        }
    }
}
