package ru.spbsetu.moipis.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import ru.spbsetu.moipis.fsm.Snapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class YamlConfigurationTests {
    private final YamlConfiguration yaml = new YamlConfiguration();

    @Test
    void readsExampleAndRoundTripsCurrentState() throws Exception {
        var source = new ClassPathResource("automaton.yaml").getContentAsString(StandardCharsets.UTF_8);
        var initial = yaml.read(source);
        assertEquals("draft", initial.currentState());
        assertEquals(3, initial.graph().transitions().size());
        var changed = new Snapshot(initial.graph(), "review");
        assertEquals(changed, yaml.read(yaml.write(changed)));
    }

    @Test
    void roundTripsRegisteredEventsAndFinalStates() {
        var snapshot = yaml.read("""
                initialState: draft
                states: [draft, approved]
                events: [approve, archive]
                finalStates: [approved]
                transitions:
                  - {from: draft, event: approve, to: approved}
                """);
        assertTrue(snapshot.graph().events().contains("archive"));
        assertTrue(snapshot.graph().finalStates().contains("approved"));
        assertEquals(snapshot, yaml.read(yaml.write(snapshot)));
    }

    @Test
    void rejectsMalformedYamlAndDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> yaml.read("["));
        assertThrows(IllegalArgumentException.class, () -> yaml.read(""));
        assertThrows(IllegalArgumentException.class, () -> yaml.read("""
                initialState: draft
                initialState: review
                states: [draft, review]
                transitions: []
                """));
    }

    @Test
    void readsDocumentExampleWithApprovalReturnAndCancellation() throws Exception {
        var config = yaml.read(Files.readString(Path.of("examples/document-approval.yaml")));
        var graph = config.graph();
        assertEquals("draft", config.currentState());
        assertEquals("review", graph.nextState("draft", "submit"));
        assertEquals("draft", graph.nextState("review", "reject"));
        assertEquals("approved", graph.nextState("review", "approve"));
        assertEquals("cancelled", graph.nextState("draft", "cancel"));
        assertEquals("cancelled", graph.nextState("review", "cancel"));
    }

    @Test
    void rejectsDuplicateStatesMissingFieldsAndUnknownProperties() {
        assertThrows(IllegalArgumentException.class, () -> yaml.read("""
                initialState: draft
                states: [draft, draft]
                transitions: []
                """));
        assertThrows(IllegalArgumentException.class, () -> yaml.read("initialState: draft"));
        assertThrows(IllegalArgumentException.class, () -> yaml.read("""
                initialState: draft
                states: [draft]
                transitions: []
                typo: true
                """));
    }
}
