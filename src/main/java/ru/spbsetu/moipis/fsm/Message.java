package ru.spbsetu.moipis.fsm;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record Message(String event, Map<String, Object> payload) {
    public Message {
        Graph.requireId(event);
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
