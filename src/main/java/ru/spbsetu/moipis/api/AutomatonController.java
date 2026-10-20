package ru.spbsetu.moipis.api;

import org.springframework.web.bind.annotation.*;
import ru.spbsetu.moipis.config.YamlConfiguration;
import ru.spbsetu.moipis.fsm.Automaton;
import ru.spbsetu.moipis.fsm.Message;
import ru.spbsetu.moipis.fsm.Snapshot;
import ru.spbsetu.moipis.fsm.Transition;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/automaton")
public class AutomatonController {
    private final Automaton automaton;
    private final YamlConfiguration yaml;

    public AutomatonController(Automaton automaton, YamlConfiguration yaml) {
        this.automaton = automaton;
        this.yaml = yaml;
    }

    @GetMapping
    public Snapshot get() {
        return automaton.snapshot();
    }

    @PutMapping(value = "/configuration", consumes = {"application/yaml", "text/yaml", "text/plain"})
    public Snapshot configure(@RequestBody String source) {
        automaton.replaceGraph(yaml.read(source).graph());
        return automaton.snapshot();
    }

    @PostMapping("/states")
    public Snapshot addState(@RequestBody StateRequest request) {
        automaton.addState(request.id());
        return automaton.snapshot();
    }

    @DeleteMapping("/states/{id}")
    public Snapshot removeState(@PathVariable String id) {
        automaton.removeState(id);
        return automaton.snapshot();
    }

    @PostMapping("/transitions")
    public Snapshot addTransition(@RequestBody Transition transition) {
        automaton.addTransition(transition);
        return automaton.snapshot();
    }

    @DeleteMapping("/transitions")
    public Snapshot removeTransition(@RequestParam String from, @RequestParam String event) {
        automaton.removeTransition(from, event);
        return automaton.snapshot();
    }

    @PostMapping("/messages")
    public CompletableFuture<Snapshot> send(@RequestBody Message message) {
        return automaton.submit(message);
    }

    public record StateRequest(String id) {}
}
