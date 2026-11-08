package ru.spbsetu.moipis.process;

import ru.spbsetu.moipis.model.Catalog;
import ru.spbsetu.moipis.model.Model.DomainObject;
import ru.spbsetu.moipis.model.Model.Parameter;
import ru.spbsetu.moipis.model.Model.Predicate;
import ru.spbsetu.moipis.model.Model.Template;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static ru.spbsetu.moipis.model.Catalog.index;
import static ru.spbsetu.moipis.model.Catalog.isComposition;

/** Interprets DNF conditions and selects aggregation strategies by name. */
public final class Conditions {
    private static final String NEGATION_PREFIX = "not ";
    private static final Set<String> OPERATORS = Set.of(
            "filled", "=", "!=", "<", "<=", ">", ">=", "in_range");

    private final Catalog catalog;
    private final Map<String, Function<List<Boolean>, Boolean>> aggregates;

    @FunctionalInterface
    public interface Expression {
        boolean evaluate(DomainObject object);
    }

    public Conditions(Catalog catalog) {
        this(catalog, Map.of(
                "all", results -> results.stream().allMatch(Boolean::booleanValue),
                "any", results -> results.stream().anyMatch(Boolean::booleanValue)));
    }

    public Conditions(Catalog catalog, Map<String, Function<List<Boolean>, Boolean>> aggregates) {
        this.catalog = catalog;
        this.aggregates = Map.copyOf(aggregates);
    }

    public Expression compile(Template template, List<List<String>> condition) {
        if (condition == null) {
            return object -> true;
        }

        Map<String, Predicate> predicates = index(template.predicates(), Predicate::id);
        List<Expression> groups = new ArrayList<>();
        for (List<String> group : condition) {
            List<Expression> terms = new ArrayList<>();
            for (String term : group) {
                terms.add(compileTerm(term, predicates));
            }
            groups.add(object -> terms.stream().allMatch(term -> term.evaluate(object)));
        }
        return object -> groups.stream().anyMatch(group -> group.evaluate(object));
    }

    public void validate(Predicate predicate) {
        if (predicate.aggregate() != null && !aggregates.containsKey(predicate.aggregate())) {
            throw new IllegalArgumentException("Unsupported aggregate: " + predicate.aggregate());
        }
        if (predicate.classId() != null) {
            return;
        }
        if (predicate.parameter() == null) {
            throw new IllegalArgumentException("Predicate parameter required: " + predicate.id());
        }

        boolean hasPath = predicate.parameter().contains(".");
        if (hasPath && (predicate.aggregate() == null || predicate.parameter().split("\\.").length != 2)) {
            throw new IllegalArgumentException("One-level path requires aggregate: " + predicate.id());
        }
        if (!OPERATORS.contains(predicate.operator())) {
            throw new IllegalArgumentException("Unsupported operator: " + predicate.operator());
        }
    }

    private Expression compileTerm(String term, Map<String, Predicate> predicates) {
        boolean negated = term.startsWith(NEGATION_PREFIX);
        String predicateId = negated ? term.substring(NEGATION_PREFIX.length()).trim() : term;
        Predicate predicate = predicates.get(predicateId);
        if (predicate == null) {
            throw new IllegalArgumentException("Unknown predicate: " + predicateId);
        }
        validate(predicate);

        Expression expression = object -> evaluatePredicate(predicate, object);
        return negated ? object -> !expression.evaluate(object) : expression;
    }

    private boolean evaluatePredicate(Predicate predicate, DomainObject object) {
        if (predicate.classId() != null) {
            return catalog.isA(object.classId(), predicate.classId());
        }

        String[] path = predicate.parameter().split("\\.");
        if (path.length == 1) {
            return evaluateParameter(predicate, object, path[0]);
        }
        return evaluateComposition(predicate, object, path[0], path[1]);
    }

    private boolean evaluateComposition(Predicate predicate, DomainObject object,
                                        String compositionName, String parameterName) {
        Object links = object.values().get(compositionName);
        if (!(links instanceof List<?> objectIds)) {
            return false;
        }

        List<Boolean> results = new ArrayList<>();
        for (Object objectId : objectIds) {
            DomainObject member = catalog.object(objectId.toString());
            results.add(evaluateParameter(predicate, member, parameterName));
        }
        Function<List<Boolean>, Boolean> aggregate = aggregates.get(predicate.aggregate());
        return aggregate.apply(List.copyOf(results));
    }

    private boolean evaluateParameter(Predicate predicate, DomainObject object, String parameterName) {
        if (object == null) {
            return false;
        }

        Parameter parameter = catalog.parameters(object.classId()).get(parameterName);
        Object value = object.values().get(parameterName);
        if (parameter == null || value == null) {
            return false;
        }

        boolean composition = isComposition(parameter);
        if (predicate.operator().equals("filled")) {
            return !composition || !((List<?>) value).isEmpty();
        }

        // All other operators compare a composition by its number of members.
        Object comparableValue = composition ? ((List<?>) value).size() : value;
        if (predicate.operator().equals("in_range")) {
            return isInRange(comparableValue, parameter);
        }
        return compare(comparableValue, predicate.operator(), predicate.value());
    }

    private boolean isInRange(Object value, Parameter parameter) {
        if (!(value instanceof Number number)) {
            return false;
        }

        boolean composition = isComposition(parameter);
        BigDecimal lowerBound = composition ? decimal(parameter.quantityMin()) : parameter.min();
        BigDecimal upperBound = composition ? decimal(parameter.quantityMax()) : parameter.max();
        BigDecimal actual = decimal(number);

        return (lowerBound == null || actual.compareTo(lowerBound) >= 0)
                && (upperBound == null || actual.compareTo(upperBound) <= 0);
    }

    private boolean compare(Object left, String operator, Object right) {
        return switch (operator) {
            case "=" -> valuesEqual(left, right);
            case "!=" -> !valuesEqual(left, right);
            case "<", "<=", ">", ">=" -> compareNumbers(left, operator, right);
            default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
        };
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return decimal(leftNumber).compareTo(decimal(rightNumber)) == 0;
        }
        return Objects.equals(left, right);
    }

    private boolean compareNumbers(Object left, String operator, Object right) {
        if (!(left instanceof Number leftNumber) || !(right instanceof Number rightNumber)) {
            return false;
        }

        int comparison = decimal(leftNumber).compareTo(decimal(rightNumber));
        return switch (operator) {
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            default -> throw new IllegalArgumentException("Unsupported numeric operator: " + operator);
        };
    }

    private static BigDecimal decimal(Number number) {
        return number == null ? null : new BigDecimal(number.toString());
    }
}
