package ru.spbsetu.moipis.config;

import org.springframework.stereotype.Component;
import ru.spbsetu.moipis.fsm.Snapshot;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

@Component
public class YamlConfiguration {
    private final YAMLMapper mapper = YAMLMapper.builder(
            YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public Snapshot read(String source) {
        try {
            AutomatonConfig config = mapper.readValue(source, AutomatonConfig.class);
            if (config == null) {
                throw new IllegalArgumentException("Configuration is required");
            }
            return config.toSnapshot();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid YAML configuration: " + exception.getMessage(), exception);
        }
    }

    public String write(Snapshot snapshot) {
        return mapper.writeValueAsString(AutomatonConfig.fromSnapshot(snapshot));
    }
}
