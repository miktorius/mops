package ru.spbsetu.moipis.process;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.spbsetu.moipis.model.Model.Instance;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Parses commands, executes them and writes one audit entry for each result. */
public final class ProcessService {
    private final ProcessEngine engine;
    private final AuditLog audit;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ProcessService(ProcessEngine engine, AuditLog audit) {
        this.engine = engine;
        this.audit = audit;
    }

    public synchronized Result sendJson(String source) {
        Object message;
        try {
            message = mapper.readValue(source, Object.class);
        } catch (RuntimeException exception) {
            var result = new Result(source, null, null, null, null, "error", "Invalid JSON command");
            audit.write(result);
            return result;
        }
        return send(message);
    }

    public synchronized Result send(Object message) {
        String user = field(message, "user");
        String object = field(message, "object");
        String stateBefore = state(object);
        String status = "accepted";
        String reason = null;

        try {
            Command command = parseCommand(message);
            command.execute(engine);
        } catch (ProcessEngine.Rejected exception) {
            status = "rejected";
            reason = exception.getMessage();
        } catch (RuntimeException exception) {
            status = "error";
            reason = exception.getMessage();
        }

        var result = new Result(message, user, object, stateBefore, state(object), status, reason);
        audit.write(result);
        return result;
    }

    private Command parseCommand(Object message) {
        String type = required(message, "type");
        String user = required(message, "user");
        String object = required(message, "object");

        return switch (type) {
            case "start" -> new Start(user, object);
            case "action" -> new Action(user, object, required(message, "action"));
            default -> throw new IllegalArgumentException("Unknown command type: " + type);
        };
    }

    private String state(String objectId) {
        Instance instance = engine.instance(objectId);
        return instance == null ? null : instance.state();
    }

    private static String field(Object message, String key) {
        if (message instanceof Map<?, ?> fields && fields.get(key) instanceof String value) {
            return value;
        }
        return null;
    }

    private static String required(Object message, String key) {
        String value = field(message, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required string field: " + key);
        }
        return value;
    }

    public record Result(
            Object command,
            String user,
            String object,
            @JsonProperty("state_before") String stateBefore,
            @JsonProperty("state_after") String stateAfter,
            String result,
            String reason
    ) {}

    public sealed interface Command permits Start, Action {
        Instance execute(ProcessEngine engine);
    }

    public record Start(String user, String object) implements Command {
        @Override
        public Instance execute(ProcessEngine engine) {
            return engine.execute("start", user, object, null);
        }
    }

    public record Action(String user, String object, String action) implements Command {
        @Override
        public Instance execute(ProcessEngine engine) {
            return engine.execute("action", user, object, action);
        }
    }
}
