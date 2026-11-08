package ru.spbsetu.moipis.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.spbsetu.moipis.config.ProcessConfiguration;
import ru.spbsetu.moipis.model.Catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the variant 1 model against its routes and domain restrictions. */
class ComponentIntroductionTests {
    private Catalog catalog;
    private ProcessService service;
    private List<ProcessService.Result> audit;

    @BeforeEach
    void loadModel() throws Exception {
        try (var input = getClass().getResourceAsStream("/process-model.yaml")) {
            var configuration = ProcessConfiguration.read(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            catalog = new Catalog(configuration);
            audit = new ArrayList<>();
            service = new ProcessService(new ProcessEngine(catalog), audit::add);
        }
    }

    private ProcessService.Result start(String object) {
        var result = service.send(Map.of("type", "start", "user", "anna", "object", object));
        assertEquals("accepted", result.result());
        assertNull(result.stateBefore());
        assertEquals("declared", result.stateAfter());
        assertSame(result, audit.getLast());
        return result;
    }

    private ProcessService.Result action(String object, String user, String action) {
        var result = service.send(Map.of("type", "action", "user", user, "object", object, "action", action));
        assertSame(result, audit.getLast());
        assertEquals(object, result.object());
        if (!result.result().equals("accepted")) {
            assertEquals(result.stateBefore(), result.stateAfter());
        }
        return result;
    }

    private void accepted(String object, String user, String action, String expectedState) {
        var result = action(object, user, action);
        assertEquals("accepted", result.result(), result.reason());
        assertEquals(expectedState, result.stateAfter());
    }

    private void reachSupplierSelection(String object) {
        start(object);
        accepted(object, "anna", "complete", "editing");
        accepted(object, "anna", "complete", "review_kb");
        accepted(object, "boris", "kb_approve", "review_to");
        accepted(object, "tatiana", "technology_approve", "suppliers");
    }

    @Test
    void serialComponentReachesPermissionAndSupportsReconsiderationOfBothDecisions() {
        String object = "component-bearing";
        reachSupplierSelection(object);
        accepted(object, "oleg", "suppliers_found", "permission");
        accepted(object, "irina", "allow", "allowed");
        accepted(object, "irina", "confirm_allowance", "permission");
        accepted(object, "irina", "forbid", "forbidden");
        accepted(object, "irina", "confirm_prohibition", "permission");
        accepted(object, "irina", "allow", "allowed");
        assertEquals(11, audit.size());
        assertTrue(catalog.template("component").states().stream().noneMatch(state -> state.terminal()));
    }

    @Test
    void customComponentNegotiatesRequirementsAndUsesSecondDnfGroup() {
        String object = "component-custom";
        reachSupplierSelection(object);
        assertEquals("rejected", action(object, "oleg", "suppliers_found").result());
        accepted(object, "oleg", "not_produced", "requirements");
        accepted(object, "oleg", "supplier_refusal", "requirements");
        assertEquals("requirements", audit.getLast().stateBefore());
        accepted(object, "oleg", "supplier_agreement", "permission");
        accepted(object, "irina", "allow", "allowed");
    }

    @Test
    void bothReviewDepartmentsCanReturnComponentAndUnauthorizedUserIsRejected() {
        String object = "component-bearing";
        start(object);
        accepted(object, "anna", "complete", "editing");
        accepted(object, "anna", "complete", "review_kb");
        assertEquals("rejected", action(object, "anna", "kb_approve").result());
        accepted(object, "boris", "kb_comments", "editing");
        accepted(object, "anna", "complete", "review_kb");
        accepted(object, "boris", "kb_approve", "review_to");
        accepted(object, "tatiana", "technology_comments", "editing");
        accepted(object, "anna", "complete", "review_kb");
    }

    @Test
    void inheritedMassOverrideRejectsComponentThatWouldFitBaseItem() {
        String object = "component-overweight";
        Number mass = (Number) catalog.object(object).values().get("mass");
        assertTrue(catalog.parameters("item").get("mass").max().doubleValue() >= mass.doubleValue());
        assertTrue(catalog.parameters("component").get("mass").max().doubleValue() < mass.doubleValue());
        start(object);
        accepted(object, "anna", "complete", "editing");
        assertEquals("rejected", action(object, "anna", "complete").result());
        assertEquals("editing", audit.getLast().stateAfter());
    }

    @Test
    void compositionRejectsEmptyListOneUnqualifiedSupplierAndInvalidMemberRange() {
        for (String object : List.of("component-no-suppliers", "component-unqualified", "component-slow-delivery")) {
            reachSupplierSelection(object);
            var result = action(object, "oleg", "suppliers_found");
            assertEquals("rejected", result.result());
            assertEquals("suppliers", result.stateAfter());
        }
    }

    @Test
    void customComponentWithoutRequirementsCannotReachPermission() {
        String object = "component-no-requirements";
        reachSupplierSelection(object);
        accepted(object, "oleg", "not_produced", "requirements");
        assertEquals("rejected", action(object, "oleg", "supplier_agreement").result());
        assertEquals("requirements", audit.getLast().stateAfter());
    }
}
