package ru.spbsetu.moipis.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ru.spbsetu.moipis.fsm.Automaton;
import ru.spbsetu.moipis.fsm.SnapshotStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:api;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class AutomatonApiTests {
    @Autowired
    MockMvc mvc;

    @Autowired
    SnapshotStore store;

    @Autowired
    Automaton automaton;

    @Test
    void updatesGraphProcessesMessagesAndPersistsResults() throws Exception {
        mvc.perform(put("/api/automaton/configuration")
                        .contentType("application/yaml")
                        .content("""
                                initialState: draft
                                states: [draft, review]
                                transitions:
                                  - {from: draft, event: submit, to: review}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/automaton/states")
                        .contentType("application/json")
                        .content("{\"id\":\"archived\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/automaton/events")
                        .contentType("application/json")
                        .content("{\"id\":\"approve\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/automaton/transitions")
                        .contentType("application/json")
                        .content("""
                                {
                                  "from": "review",
                                  "event": "approve",
                                  "to": "archived"
                                }
                                """))
                .andExpect(status().isOk());

        var request = mvc.perform(post("/api/automaton/messages")
                        .contentType("application/json")
                        .content("""
                                {
                                  "event": "submit",
                                  "payload": {
                                    "document": 123
                                  }
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentState").value("review"));

        mvc.perform(delete("/api/automaton/states/review"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/automaton/transitions")
                        .param("from", "review")
                        .param("event", "approve"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/automaton/states/archived"))
                .andExpect(status().isOk());

        assertEquals(automaton.snapshot(), store.load().orElseThrow());

        mvc.perform(put("/api/automaton/initial-state")
                        .contentType("application/json")
                        .content("{\"id\":\"review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.initialState").value("review"));

        mvc.perform(put("/api/automaton/final-state")
                        .contentType("application/json")
                        .content("{\"id\":\"review\",\"terminal\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.finalStates[0]").value("review"));

        assertEquals(automaton.snapshot(), store.load().orElseThrow());

        mvc.perform(delete("/api/automaton/events/submit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.transitions").isEmpty());

        mvc.perform(delete("/api/automaton/events/missing"))
                .andExpect(status().isBadRequest());

        assertEquals(automaton.snapshot(), store.load().orElseThrow());

        var unknown = mvc.perform(post("/api/automaton/messages")
                        .contentType("application/json")
                        .content("{\"event\":\"unknown\"}"))
                .andReturn();

        mvc.perform(asyncDispatch(unknown))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/automaton"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentState").value("review"));
    }
}
