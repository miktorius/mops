package ru.spbsetu.moipis.model;

import ru.spbsetu.moipis.model.Model.Configuration;
import ru.spbsetu.moipis.model.Model.DomainObject;
import ru.spbsetu.moipis.model.Model.ObjectClass;
import ru.spbsetu.moipis.model.Model.Parameter;
import ru.spbsetu.moipis.model.Model.State;
import ru.spbsetu.moipis.model.Model.Template;
import ru.spbsetu.moipis.model.Model.User;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves inherited parameters and initial object values at startup.
 */
public final class Catalog {
    private static final Set<String> SCALAR_TYPES = Set.of("integer", "decimal", "string");

    private final Configuration configuration;
    private final Map<String, ObjectClass> classes;
    private final Map<String, User> users;
    private final Map<String, Map<String, Parameter>> resolvedParameters = new LinkedHashMap<>();
    private final Map<String, DomainObject> objects = new LinkedHashMap<>();
    private final Map<String, Template> templates = new LinkedHashMap<>();

    public Catalog(Configuration configuration) {
        this.configuration = configuration;
        this.classes = index(configuration.classes(), ObjectClass::id);
        this.users = index(configuration.users(), User::id);

        for (String classId : classes.keySet()) {
            resolveParameters(classId, new HashSet<>());
        }
        loadObjects(configuration.objects());
        loadTemplates(configuration.processes());
    }

    public Map<String, Parameter> parameters(String classId) {
        return resolvedParameters.get(classId);
    }

    public DomainObject object(String objectId) {
        return objects.get(objectId);
    }

    public User user(String userId) {
        return users.get(userId);
    }

    public Template template(String classId) {
        return templates.get(classId);
    }

    public Collection<DomainObject> objects() {
        return Collections.unmodifiableCollection(objects.values());
    }

    public Configuration configuration() {
        return configuration;
    }

    public boolean isA(String actualClassId, String expectedClassId) {
        for (String classId = actualClassId; classId != null; classId = classes.get(classId).parent()) {
            if (classId.equals(expectedClassId)) {
                return true;
            }
        }
        return false;
    }

    private void loadObjects(List<DomainObject> initialObjects) {
        for (DomainObject object : list(initialObjects)) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Parameter parameter : parameters(object.classId()).values()) {
                values.put(parameter.id(), initialValue(object, parameter));
            }

            var loadedObject = new DomainObject(
                    object.id(), object.classId(), Collections.unmodifiableMap(values));
            if (objects.putIfAbsent(object.id(), loadedObject) != null) {
                throw new IllegalArgumentException("Duplicate object: " + object.id());
            }
        }
    }

    private Object initialValue(DomainObject object, Parameter parameter) {
        // containsKey distinguishes an explicit null from an omitted value.
        boolean explicitlySet = object.values() != null && object.values().containsKey(parameter.id());
        Object value = explicitlySet ? object.values().get(parameter.id()) : parameter.defaultValue();

        if (isComposition(parameter) && value == null) {
            return List.of();
        }
        return value;
    }

    private void loadTemplates(List<Template> processTemplates) {
        for (Template template : list(processTemplates)) {
            if (!classes.containsKey(template.classId())) {
                throw new IllegalArgumentException("Unknown class: " + template.classId());
            }
            if (templates.putIfAbsent(template.classId(), template) != null) {
                throw new IllegalArgumentException("Multiple templates for class: " + template.classId());
            }

            long initialStateCount = list(template.states()).stream().filter(State::initial).count();
            if (initialStateCount != 1) {
                throw new IllegalArgumentException("Exactly one initial state required: " + template.process());
            }
        }
    }

    private Map<String, Parameter> resolveParameters(String classId, Set<String> visiting) {
        if (resolvedParameters.containsKey(classId)) {
            return resolvedParameters.get(classId);
        }

        ObjectClass objectClass = classes.get(classId);
        if (objectClass == null || !visiting.add(classId)) {
            throw new IllegalArgumentException("Unknown or cyclic parent: " + classId);
        }

        Map<String, Parameter> parameters = new LinkedHashMap<>();
        if (objectClass.parent() != null) {
            parameters.putAll(resolveParameters(objectClass.parent(), visiting));
        }
        for (Parameter parameter : list(objectClass.parameters())) {
            Parameter inherited = parameters.get(parameter.id());
            if (inherited != null && !inherited.type().equals(parameter.type())) {
                throw new IllegalArgumentException("Cannot change inherited type: " + parameter.id());
            }
            parameters.put(parameter.id(), parameter);
        }

        visiting.remove(classId);
        Map<String, Parameter> result = Collections.unmodifiableMap(parameters);
        resolvedParameters.put(classId, result);
        return result;
    }

    public static boolean isComposition(Parameter parameter) {
        return !SCALAR_TYPES.contains(parameter.type());
    }

    public static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    public static <T> Map<String, T> index(List<T> items, Function<T, String> identifier) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T item : list(items)) {
            String id = identifier.apply(item);
            if (result.putIfAbsent(id, item) != null) {
                throw new IllegalArgumentException("Duplicate identifier: " + id);
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
