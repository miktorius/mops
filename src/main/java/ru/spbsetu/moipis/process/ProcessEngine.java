package ru.spbsetu.moipis.process;

import ru.spbsetu.moipis.model.Catalog;
import ru.spbsetu.moipis.model.Model.Instance;
import ru.spbsetu.moipis.model.Model.State;
import ru.spbsetu.moipis.model.Model.Template;
import ru.spbsetu.moipis.model.Model.Transition;
import ru.spbsetu.moipis.model.Model.User;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ru.spbsetu.moipis.model.Catalog.list;

/** Executes processes synchronously and stores their current states separately from templates. */
public final class ProcessEngine {
    private final Catalog catalog;
    private final Map<String, Instance> instances = new LinkedHashMap<>();
    private final Map<Transition, Conditions.Expression> conditions = new IdentityHashMap<>();

    public ProcessEngine(Catalog catalog) {
        this(catalog, new Conditions(catalog));
    }

    public ProcessEngine(Catalog catalog, Conditions interpreter) {
        this.catalog = catalog;
        for (Template template : list(catalog.configuration().processes())) {
            list(template.predicates()).forEach(interpreter::validate);
            for (Transition transition : list(template.transitions())) {
                conditions.put(transition, interpreter.compile(template, transition.condition()));
            }
        }
    }

    public Instance execute(String type, String userId, String objectId, String action) {
        User user = catalog.user(userId);
        var object = catalog.object(objectId);
        require(user != null, "Unknown user: " + userId);
        require(object != null, "Unknown object: " + objectId);

        Template template = catalog.template(object.classId());
        require(template != null, "No template for exact class: " + object.classId());

        if (type.equals("start")) {
            return start(user, objectId, template);
        }
        return act(user, objectId, action, template);
    }

    public Instance instance(String objectId) {
        return instances.get(objectId);
    }

    public List<Instance> instances() {
        return List.copyOf(instances.values());
    }

    private Instance start(User user, String objectId, Template template) {
        require(!instances.containsKey(objectId), "Process already started");
        authorize(user, template.createRoles());

        State initialState = template.states().stream()
                .filter(State::initial)
                .findFirst()
                .orElseThrow();
        var instance = new Instance(objectId, template.process(), initialState.id());
        instances.put(objectId, instance);
        return instance;
    }

    private Instance act(User user, String objectId, String action, Template template) {
        Instance current = instances.get(objectId);
        require(current != null, "Process not started");

        State currentState = template.states().stream()
                .filter(state -> state.id().equals(current.state()))
                .findFirst()
                .orElseThrow();
        require(!currentState.terminal(), "Process is completed");
        authorize(user, currentState.roles());

        Transition transition = selectTransition(template, current, action);
        var next = new Instance(objectId, template.process(), transition.to());
        instances.put(objectId, next);
        return next;
    }

    private Transition selectTransition(Template template, Instance current, String action) {
        List<Transition> candidates = list(template.transitions()).stream()
                .filter(transition -> transition.from().equals(current.state()))
                .filter(transition -> transition.action().equals(action))
                .toList();
        require(!candidates.isEmpty(), "No transition for action: " + action);

        var object = catalog.object(current.object());
        List<Transition> matches = candidates.stream()
                .filter(transition -> conditions.get(transition).evaluate(object))
                .toList();
        require(!matches.isEmpty(), "Transition condition is false");
        if (matches.size() > 1) {
            throw new IllegalStateException("Ambiguous transition: " + action);
        }
        return matches.getFirst();
    }

    private static void authorize(User user, List<String> allowedRoles) {
        boolean hasAllowedRole = list(user.roles()).stream().anyMatch(list(allowedRoles)::contains);
        require(hasAllowedRole, "Required role: " + list(allowedRoles));
    }

    private static void require(boolean allowed, String reason) {
        if (!allowed) {
            throw new Rejected(reason);
        }
    }

    public static final class Rejected extends RuntimeException {
        Rejected(String message) {
            super(message);
        }
    }
}
