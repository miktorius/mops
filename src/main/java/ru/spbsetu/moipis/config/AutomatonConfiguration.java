package ru.spbsetu.moipis.config;

import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import ru.spbsetu.moipis.fsm.Automaton;
import ru.spbsetu.moipis.fsm.SnapshotStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class AutomatonConfiguration {
    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    Automaton automaton(SnapshotStore store, YamlConfiguration yaml) throws IOException {
        var source = new ClassPathResource("automaton.yaml").getContentAsString(StandardCharsets.UTF_8);
        var automaton = new Automaton(store, yaml.read(source).graph());
        var logger = LoggerFactory.getLogger(AutomatonConfiguration.class);
        for (var event : List.of("submit", "approve", "reject", "cancel")) {
            if (!automaton.snapshot().graph().events().contains(event)) {
                continue;
            }
            automaton.registerHandler(event, message -> logger.info("Handled event {}: {}",
                    message.event(), message.payload()));
        }
        return automaton;
    }
}
