package ru.spbsetu.moipis.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Configuration data only; domain names never enter the execution code.
 */
public final class Model {
    private Model() {
    }

    public record Configuration(
            List<ObjectClass> classes,
            List<DomainObject> objects,
            List<Role> roles,
            List<User> users,
            List<Template> processes
    ) {
    }

    public record ObjectClass(
            String id,
            String title,
            String parent,
            List<Parameter> parameters
    ) {
    }

    public record Parameter(
            String id,
            String title,
            String type,
            BigDecimal min,
            BigDecimal max,
            @JsonProperty("default") Object defaultValue,
            @JsonProperty("quantity_min") Integer quantityMin,
            @JsonProperty("quantity_max") Integer quantityMax
    ) {
    }

    public record DomainObject(
            String id,
            @JsonProperty("class") String classId,
            Map<String, Object> values
    ) {
    }

    public record Role(String id, String title) {
    }

    public record User(String id, String name, List<String> roles) {
    }

    public record Template(
            String process,
            String title,
            @JsonProperty("class") String classId,
            @JsonProperty("create_roles") List<String> createRoles,
            List<Action> actions,
            List<Predicate> predicates,
            List<State> states,
            List<Transition> transitions
    ) {
    }

    public record Action(String id, String title) {
    }

    public record State(
            String id,
            String title,
            boolean initial,
            @JsonProperty("final") boolean terminal,
            List<String> roles
    ) {
    }

    public record Predicate(
            String id,
            String parameter,
            String operator,
            Object value,
            String aggregate,
            @JsonProperty("class") String classId
    ) {
    }

    public record Transition(
            String from,
            String action,
            String to,
            List<List<String>> condition
    ) {
    }

    /**
     * A process run holds identifiers and state, never a copy of its template.
     */
    public record Instance(String object, String template, String state) {
    }
}
