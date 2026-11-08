package ru.spbsetu.moipis.process;

import ru.spbsetu.moipis.config.ProcessConfiguration;
import ru.spbsetu.moipis.model.Catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.spbsetu.moipis.model.Model.*;

class ProcessTests {
    private String yaml;
    private Catalog catalog;
    private ProcessEngine engine;
    private ProcessService service;
    private List<ProcessService.Result> log;

    @BeforeEach
    void setup() throws Exception {
        try (var input = getClass().getResourceAsStream("/engine-model.yaml")) {
            yaml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        configure(yaml);
    }
    private void configure(String source) {
        catalog = new Catalog(ProcessConfiguration.read(source));
        engine = new ProcessEngine(catalog);
        log = new ArrayList<>();
        service = new ProcessService(engine, log::add);
    }
    private ProcessService.Result command(String type, String user, String object, String action) {
        var input = new LinkedHashMap<String, Object>();
        input.put("type", type);
        input.put("user", user);
        input.put("object", object);
        if (action != null) {
            input.put("action", action);
        }
        var result = service.send(input);
        assertSame(result, log.getLast());
        assertEquals(input, result.command());
        var instance = engine.instance(object);
        assertEquals(instance == null ? null : instance.state(), result.stateAfter());
        if (!result.result().equals("accepted")) {
            assertEquals(result.stateBefore(), result.stateAfter());
        }
        return result;
    }
    private ProcessService.Result start(String object) { return command("start", "author", object, null); }
    private ProcessService.Result act(String user, String object, String action) { return command("action", user, object, action); }
    private void review(String object) {
        assertEquals("accepted", start(object).result());
        assertEquals("review", act("author", object, "send").stateAfter());
    }
    @Test
    void completesRouteReturnsAndChecksRoles() {
        var initial = start("good");
        assertNull(initial.stateBefore());
        assertEquals("initial", initial.stateAfter());
        assertEquals("review", act("author", "good", "send").stateAfter());
        assertEquals("rejected", act("author", "good", "accept").result());
        assertEquals("initial", act("reviewer", "good", "back").stateAfter());
        assertEquals("review", act("both", "good", "send").stateAfter());
        assertEquals("done", act("both", "good", "accept").stateAfter());
        assertEquals("rejected", act("both", "good", "back").result());
        assertEquals("rejected", start("good").result());
        assertEquals(8, log.size());
    }
    @Test
    void appliesDefaultsAndFullyOverridesInheritedParameters() {
        var countParameter = catalog.parameters("child").get("count");
        assertEquals(5, countParameter.max().intValueExact());
        assertEquals(2, catalog.object("good").values().get("count"));
        assertEquals("", catalog.object("good").values().get("name"));
        assertNull(catalog.object("missing").values().get("count"));
        assertNull(catalog.object("good").values().get("unset"));
        assertEquals(List.of(), catalog.object("empty").values().get("parts"));
        assertEquals(countParameter, catalog.parameters("grandchild").get("count"));
        configure(yaml.replace("min: 2, max: 5, default: 2", "max: 5"));
        countParameter = catalog.parameters("child").get("count");
        assertNull(countParameter.min());
        assertNull(countParameter.defaultValue());
        assertNull(catalog.object("good").values().get("count"));
    }
    @Test
    void checksInheritedBoundsAndCompositionMembersActualClassesAtTransitionTime() {
        for (String object : List.of("big", "bad_part", "missing", "empty")) {
            assertEquals("accepted", start(object).result());
            var result = act("author", object, "send");
            assertEquals("rejected", result.result());
            assertEquals("initial", result.stateAfter());
        }
    }
    @Test
    void templatesAreNotInheritedAndRestartClearsInstances() {
        assertEquals("rejected", start("inherited").result());
        review("good");
        var restarted = new ProcessEngine(new Catalog(ProcessConfiguration.read(yaml)));
        assertTrue(restarted.instances().isEmpty());
    }
    @Test
    void evaluatesBothDnfGroupsAndNegationWithoutCrossObjectState() {
        review("good");
        review("alternate");
        review("ordinary");
        assertEquals("done", act("reviewer", "good", "accept").stateAfter());
        assertEquals("done", act("reviewer", "alternate", "accept").stateAfter());
        assertEquals("extra", act("reviewer", "ordinary", "accept").stateAfter());
        assertEquals("done", act("reviewer", "ordinary", "accept").stateAfter());
    }
    @Test
    void multipleTrueDnfGroupsStillSelectOneTransition() {
        configure(yaml.replace("count: 4, mode: fast", "count: 2, mode: fast"));
        review("alternate");
        assertEquals("accepted", act("reviewer", "alternate", "accept").result());
    }
    @Test
    void ambiguousTransitionsProduceAuditedErrorAndPreserveState() {
        configure(yaml.replace("[[not small, not fast]]", "[[small]]"));
        review("good");
        var result = act("reviewer", "good", "accept");
        assertEquals("error", result.result());
        assertEquals("review", result.stateAfter());
        assertTrue(result.reason().contains("Ambiguous"));
    }
    @Test
    void logsMalformedJsonAndInvalidCommands() {
        for (String input : List.of("", "{", "null", "[]", "{}", "{\"type\":42}", "{} {}")) {
            var result = service.sendJson(input);
            assertEquals("error", result.result());
        assertNotNull(result.reason());
            assertSame(result, log.getLast());
        }
        assertEquals("error", command("unknown", "author", "good", null).result());
        assertEquals(8, log.size());
    }
    @Test
    void rejectsUnknownReferencesUnstartedActionsAndMissingRoles() {
        assertEquals("rejected", command("start", "nobody", "good", null).result());
        assertEquals("rejected", start("unknown").result());
        assertEquals("rejected", command("start", "reviewer", "good", null).result());
        assertEquals("rejected", act("author", "good", "send").result());
        start("good");
        assertEquals("rejected", act("author", "good", "unknown").result());
    }
    private boolean predicate(String object, String parameter, String operator, Object value, String aggregate) {
        var predicateDefinition = new Predicate("p", parameter, operator, value, aggregate, null);
        var template = new Template("test", null, "child", List.of(), List.of(), List.of(predicateDefinition), List.of(), List.of());
        return new Conditions(catalog).compile(template, List.of(List.of("p"))).evaluate(catalog.object(object));
    }
    @Test
    void operatorsHandleNumbersStringsMissingValuesAndCompositionCounts() {
        assertTrue(predicate("good", "count", "=", 2.0, null));
        assertFalse(predicate("good", "count", "!=", 2, null));
        assertTrue(predicate("good", "count", "<", 3, null));
        assertTrue(predicate("good", "count", "<=", 2, null));
        assertTrue(predicate("good", "count", ">", 1, null));
        assertTrue(predicate("good", "count", ">=", 2, null));
        assertTrue(predicate("good", "name", "filled", null, null));
        assertTrue(predicate("good", "name", "=", "", null));
        assertTrue(predicate("good", "parts", "=", 1, null));
        assertTrue(predicate("good", "parts", ">=", 1, null));
        for (String operator : List.of("filled", "=", "!=", "<", "in_range")) {
            assertFalse(predicate("missing", "count", operator, 1, null));
            assertFalse(predicate("good", "absent", operator, 1, null));
        }
    }
    @Test
    void aggregationHandlesEmptySetsMissingValuesAndRegisteredStrategies() {
        assertTrue(predicate("empty", "parts.size", "in_range", null, "all"));
        assertFalse(predicate("empty", "parts.size", "in_range", null, "any"));
        assertTrue(predicate("good", "parts.size", "in_range", null, "all"));
        assertFalse(predicate("bad_part", "parts.size", "in_range", null, "all"));
        assertFalse(predicate("good", "parts.absent", "filled", null, "all"));
        assertThrows(IllegalArgumentException.class, () -> predicate("good", "parts.size", "filled", null, "bogus"));
        assertThrows(IllegalArgumentException.class, () -> predicate("good", "parts.size", "filled", null, null));
        var predicateDefinition = new Predicate("p", "parts.size", "in_range", null, "custom", null);
        var template = new Template("test", null, "child", null, null, List.of(predicateDefinition), null, null);
        var interpreter = new Conditions(catalog, Map.of("custom", results -> results.contains(true)));
        assertTrue(interpreter.compile(template, List.of(List.of("p"))).evaluate(catalog.object("good")));
    }
    @Test
    void classPredicateIncludesSubclassesAndNegatesMissingValues() {
        var predicateDefinition = new Predicate("p", null, null, null, null, "base");
        var missing = new Predicate("missing", "unset", "filled", null, null, null);
        var template = new Template("test", null, "child", null, null, List.of(predicateDefinition, missing), null, null);
        assertTrue(new Conditions(catalog).compile(template, List.of(List.of("p", "not missing")))
                .evaluate(catalog.object("good")));
    }
    @Test
    void rejectsUnsupportedAggregatorsAtStartupEvenWhenUnused() {
        assertThrows(IllegalArgumentException.class, () -> configure(yaml.replace("aggregate: all", "aggregate: other")));
    }
    @Test
    void jsonLinesPersistsOneRecordPerCommandAndAppends(@TempDir Path dir) throws Exception {
        var path = dir.resolve("audit.jsonl");
        try (var audit = new AuditLog.JsonLines(path)) {
            var service = new ProcessService(engine, audit);
            service.sendJson("{\"type\":\"start\",\"user\":\"author\",\"object\":\"good\"}");
            service.sendJson("{\"type\":\"action\",\"user\":\"reviewer\",\"object\":\"good\",\"action\":\"send\"}");
            service.sendJson("{");
        }
        var lines = Files.readAllLines(path);
        assertEquals(3, lines.size());
        var mapper = JsonMapper.builder().build();
        var accepted = mapper.readTree(lines.getFirst());
        assertTrue(accepted.get("state_before").isNull());
        assertEquals("initial", accepted.get("state_after").asString());
        var rejected = mapper.readTree(lines.get(1));
        assertEquals("rejected", rejected.get("result").asString());
        assertEquals(rejected.get("state_before"), rejected.get("state_after"));
        assertEquals("error", mapper.readTree(lines.get(2)).get("result").asString());
        try (var audit = new AuditLog.JsonLines(path)) {
            new ProcessService(engine, audit).sendJson("null");
        }
        assertEquals(4, Files.readAllLines(path).size());
    }
}
