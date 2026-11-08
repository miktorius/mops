package ru.spbsetu.moipis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"mops.model=classpath:engine-model.yaml", "mops.audit=build/test-audit.jsonl"})
@AutoConfigureMockMvc
class MopsApplicationTests {
    @Autowired MockMvc mvc;
    @Test void loadsModelServesUiAndExecutesAuditedCommandsWithoutDatabase() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/api/processes/model")).andExpect(status().isOk())
                .andExpect(jsonPath("$.processes[0].class").value("child"));
        mvc.perform(get("/api/processes/objects")).andExpect(status().isOk())
                .andExpect(jsonPath("$[2].values.count").value(2));
        mvc.perform(post("/api/processes/commands").contentType("application/json")
                        .content("{\"type\":\"start\",\"user\":\"author\",\"object\":\"good\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("accepted"))
                .andExpect(jsonPath("$.state_after").value("initial"));
        mvc.perform(post("/api/processes/commands").contentType("application/json").content("{"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("error"));
        mvc.perform(post("/api/processes/commands").contentType("application/json").content(""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("error"));
        mvc.perform(get("/api/processes")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("initial"));
    }
}
