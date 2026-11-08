package ru.spbsetu.moipis.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.spbsetu.moipis.model.Catalog;
import ru.spbsetu.moipis.model.Model;
import ru.spbsetu.moipis.process.ProcessEngine;
import ru.spbsetu.moipis.process.ProcessService;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/processes")
public class ProcessController {
    private final ProcessService service;
    private final ProcessEngine engine;
    private final Catalog catalog;

    public ProcessController(ProcessService service, ProcessEngine engine, Catalog catalog) {
        this.service = service;
        this.engine = engine;
        this.catalog = catalog;
    }

    @PostMapping(value = "/commands", consumes = "application/json")
    public ProcessService.Result command(@RequestBody(required = false) String source) {
        return service.sendJson(source);
    }

    @GetMapping
    public List<Model.Instance> instances() {
        // Use the same lock as command execution to read a consistent snapshot.
        synchronized (service) {
            return engine.instances();
        }
    }

    @GetMapping("/model")
    public Model.Configuration model() {
        return catalog.configuration();
    }

    @GetMapping("/objects")
    public Collection<Model.DomainObject> objects() {
        return catalog.objects();
    }
}
