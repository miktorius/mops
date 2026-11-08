package ru.spbsetu.moipis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import ru.spbsetu.moipis.model.Catalog;
import ru.spbsetu.moipis.model.Model;
import ru.spbsetu.moipis.process.AuditLog;
import ru.spbsetu.moipis.process.ProcessEngine;
import ru.spbsetu.moipis.process.ProcessService;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Configuration
public class ProcessConfiguration {
    public static Model.Configuration read(String source) {
        YAMLFactory factory = YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        YAMLMapper mapper = YAMLMapper.builder(factory)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        return mapper.readValue(source, Model.Configuration.class);
    }

    @Bean
    Catalog catalog(@Value("${mops.model:classpath:process-model.yaml}") Resource source) throws IOException {
        String yaml = source.getContentAsString(StandardCharsets.UTF_8);
        return new Catalog(read(yaml));
    }

    @Bean
    ProcessEngine processEngine(Catalog catalog) {
        return new ProcessEngine(catalog);
    }

    @Bean(destroyMethod = "close")
    AuditLog.JsonLines auditLog(@Value("${mops.audit:logs/processes.jsonl}") String path) throws IOException {
        return new AuditLog.JsonLines(Path.of(path));
    }

    @Bean
    ProcessService processService(ProcessEngine engine, AuditLog audit) {
        return new ProcessService(engine, audit);
    }
}
