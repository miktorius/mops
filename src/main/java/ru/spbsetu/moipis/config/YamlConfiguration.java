package ru.spbsetu.moipis.config;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import ru.spbsetu.moipis.fsm.Graph;
import ru.spbsetu.moipis.fsm.Snapshot;
import ru.spbsetu.moipis.fsm.Transition;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class YamlConfiguration {
    public Snapshot read(String source) {
        try {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            var root = map(new Yaml(new SafeConstructor(options)).load(source));
            var states = new LinkedHashSet<String>();
            for (var state : list(root.get("states"))) {
                if (!states.add(text(state))) throw new IllegalArgumentException("Duplicate state");
            }
            var transitions = list(root.get("transitions")).stream().map(value -> {
                var transition = map(value);
                return new Transition(text(transition.get("from")), text(transition.get("event")),
                        text(transition.get("to")));
            }).toList();
            var graph = new Graph(text(root.get("initialState")), states, transitions);
            var current = root.containsKey("currentState") ? text(root.get("currentState")) : graph.initialState();
            return new Snapshot(graph, current);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid YAML configuration: " + exception.getMessage(), exception);
        }
    }

    public String write(Snapshot snapshot) {
        var root = new LinkedHashMap<String, Object>();
        root.put("initialState", snapshot.graph().initialState());
        root.put("currentState", snapshot.currentState());
        root.put("states", snapshot.graph().states().stream().sorted().toList());
        root.put("transitions", snapshot.graph().transitions().stream()
                .map(t -> Map.of("from", t.from(), "event", t.event(), "to", t.to())).toList());
        return new Yaml().dump(root);
    }

    private static Map<?, ?> map(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Expected a mapping");
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException("Expected a list");
    }

    private static String text(Object value) {
        if (value instanceof String text) return text;
        throw new IllegalArgumentException("Expected a string identifier");
    }
}
