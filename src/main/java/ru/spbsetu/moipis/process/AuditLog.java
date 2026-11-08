package ru.spbsetu.moipis.process;

import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@FunctionalInterface
public interface AuditLog {
    void write(ProcessService.Result entry);

    /**
     * Append-only JSONL. The application owns and closes this resource.
     */
    final class JsonLines implements AuditLog, AutoCloseable {
        private final BufferedWriter writer;
        private final JsonMapper mapper = JsonMapper.builder().build();

        public JsonLines(Path path) throws IOException {
            Path parentDirectory = path.toAbsolutePath().getParent();
            if (parentDirectory != null) {
                Files.createDirectories(parentDirectory);
            }
            writer = Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        @Override
        public synchronized void write(ProcessService.Result entry) {
            try {
                writer.write(mapper.writeValueAsString(entry));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot write audit log", exception);
            }
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
