package ru.spbsetu.moipis.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import ru.spbsetu.moipis.fsm.Snapshot;

import java.nio.charset.StandardCharsets;

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
}
