package dev.blipe.exprdsl;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class ExprDslValueTest {
    record Person(String name, long age) {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static ExprDsl.Value randomJsonValue(Random random, int depth) {
        int choice = depth >= 3 ? random.nextInt(5) : random.nextInt(7);
        return switch (choice) {
            case 0 -> ExprDsl.NullValue.INSTANCE;
            case 1 -> ExprDsl.Values.booleanValue(random.nextBoolean());
            case 2 -> ExprDsl.Values.longValue(random.nextInt(20_001) - 10_000L);
            case 3 -> ExprDsl.Values.decimalValue(random.nextInt(1000) + "." + random.nextInt(1000));
            case 4 -> ExprDsl.Values.stringValue("s" + random.nextInt(10_000));
            case 5 -> {
                int size = random.nextInt(5);
                java.util.ArrayList<ExprDsl.Value> items = new java.util.ArrayList<>();
                for (int i = 0; i < size; i++) items.add(randomJsonValue(random, depth + 1));
                yield new ExprDsl.ArrayValue(items);
            }
            default -> {
                int size = random.nextInt(5);
                LinkedHashMap<String, ExprDsl.Value> fields = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) fields.put("k" + i, randomJsonValue(random, depth + 1));
                yield new ExprDsl.ObjectValue(fields);
            }
        };
    }

    public static void main(String[] args) throws Throwable {
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard();

        ExprDsl.CompiledExpression literal = ExprDsl.Compiler.compile(
                "{name:'Jason', values:[1, 2.50, null]}", functions);
        ExprDsl.Value literalValue = literal.evalValue(
                new ExprDsl.MapEvalContext(Map.of(), functions));
        check(literalValue instanceof ExprDsl.ObjectValue, "object literal must be ObjectValue");
        ExprDsl.ObjectValue object = (ExprDsl.ObjectValue) literalValue;
        check(object.get("values") instanceof ExprDsl.ArrayValue, "array literal must be ArrayValue");
        ExprDsl.ArrayValue values = (ExprDsl.ArrayValue) object.get("values");
        check(values.get(1) instanceof ExprDsl.DecimalValue, "decimal literal must be lossless DecimalValue");
        equal("2.50", ((ExprDsl.DecimalValue) values.get(1)).lexeme(), "decimal lexeme");
        check(values.get(2) == ExprDsl.NullValue.INSTANCE, "JSON null must be NullValue");

        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("presentNull", null);
        Map<String, Object> variables = Map.of("user", user);
        ExprDsl.MapEvalContext context = new ExprDsl.MapEvalContext(variables, functions);

        ExprDsl.Value missing = ExprDsl.Compiler.compile("user.missing", functions)
                .evalValue(context);
        ExprDsl.Value presentNull = ExprDsl.Compiler.compile("user.presentNull", functions)
                .evalValue(context);
        check(missing == ExprDsl.MissingValue.INSTANCE, "missing property must be MISSING");
        check(presentNull == ExprDsl.NullValue.INSTANCE, "present JSON null must be NULL");
        equal("fallback", ExprDsl.Compiler.compile("user.missing ?? 'fallback'", functions).eval(context),
                "missing coalescing");
        equal("fallback", ExprDsl.Compiler.compile("user.presentNull ?? 'fallback'", functions).eval(context),
                "null coalescing");
        equal(Boolean.FALSE, ExprDsl.Compiler.compile("user.missing == null", functions).eval(context),
                "missing and null differ by default");

        ExprDsl.NullPolicy missingEqualsNull = new ExprDsl.NullPolicy(
                true, true, true, false, false);
        ExprDsl.EvaluationPolicy equalityPolicy = new ExprDsl.EvaluationPolicy(
                ExprDsl.MissingPropertyMode.MISSING,
                missingEqualsNull,
                ExprDsl.NumberPolicy.LOSSLESS_JSON);
        equal(Boolean.TRUE,
                ExprDsl.Compiler.compile("user.missing == null", functions,
                                new ExprDsl.CompileOptions(Map.of(), false, false,
                                        ExprDsl.ExecutionLimits.DEFAULT, equalityPolicy, true))
                        .eval(new ExprDsl.MapEvalContext(variables, functions, equalityPolicy)),
                "policy can equate missing and null");

        ExprDsl.NullPolicy doNotCoalesceMissing = new ExprDsl.NullPolicy(
                false, false, true, true, false);
        ExprDsl.EvaluationPolicy noMissingCoalesce = new ExprDsl.EvaluationPolicy(
                ExprDsl.MissingPropertyMode.MISSING,
                doNotCoalesceMissing,
                ExprDsl.NumberPolicy.LOSSLESS_JSON);
        ExprDsl.CompileOptions noMissingOptions = new ExprDsl.CompileOptions(
                Map.of(), false, false, ExprDsl.ExecutionLimits.DEFAULT, noMissingCoalesce, true);
        ExprDsl.Value notCoalesced = ExprDsl.Compiler.compile(
                        "user.missing ?? 'fallback'", functions, noMissingOptions)
                .evalValue(new ExprDsl.MapEvalContext(variables, functions, noMissingCoalesce));
        check(notCoalesced == ExprDsl.MissingValue.INSTANCE, "missing coalesce policy must be honored");
        ExprDsl.Value safeMissing = ExprDsl.Compiler.compile(
                        "null?.name", functions, noMissingOptions)
                .evalValue(new ExprDsl.MapEvalContext(Map.of(), functions, noMissingCoalesce));
        check(safeMissing == ExprDsl.MissingValue.INSTANCE, "safe access policy can return MISSING");

        ExprDsl.DslSchema userSchema = ExprDsl.DslSchema.object(Map.of(
                "name", ExprDsl.DslSchema.string(),
                "age", ExprDsl.DslSchema.longType()));
        ExprDsl.CompileOptions schemaOptions = ExprDsl.CompileOptions.DEFAULT
                .withSchemas(Map.of("user", userSchema));
        ExprDsl.Analysis analysis = ExprDsl.Compiler.analyze("user.name", functions, schemaOptions);
        equal(ExprDsl.DslType.STRING, analysis.resultType(), "schema property type");
        try {
            ExprDsl.Compiler.analyze("user.nmae", functions, schemaOptions);
            throw new AssertionError("schema typo should fail");
        } catch (ExprDsl.CompileException expected) {
            check(expected.diagnostics().stream()
                            .anyMatch(d -> d.code().equals("S_UNKNOWN_PROPERTY")),
                    "schema typo diagnostic");
        }

        ExprDsl.Value lossless = ExprDsl.Json.parse(
                "1.2300e+2", 32, ExprDsl.NumberPolicy.LOSSLESS_JSON);
        check(lossless instanceof ExprDsl.DecimalValue, "lossless JSON number type");
        equal("1.2300e+2", ((ExprDsl.DecimalValue) lossless).lexeme(), "lossless JSON lexeme");
        equal("1.2300e+2", ExprDsl.Json.write(lossless), "lossless JSON write");
        check(ExprDsl.Json.parse("1.25", 32, ExprDsl.NumberPolicy.LONG_DOUBLE)
                        instanceof ExprDsl.DoubleValue,
                "LONG_DOUBLE policy");
        check(ExprDsl.Json.parse("1.25", 32, ExprDsl.NumberPolicy.BIG_DECIMAL)
                        instanceof ExprDsl.DecimalValue,
                "BIG_DECIMAL policy");

        ExprDsl.Value decimalResult = ExprDsl.Compiler.compile("0.10 + 0.20", functions)
                .evalValue(new ExprDsl.MapEvalContext(Map.of(), functions));
        check(decimalResult instanceof ExprDsl.DecimalValue, "decimal arithmetic remains decimal");
        equal(new BigDecimal("0.3"), ExprDsl.Values.toJava(decimalResult), "exact decimal arithmetic");

        ExprDsl.Value reflected = ExprDsl.ValueAdapters.REFLECTIVE.toValue(new Person("Jason", 50));
        check(reflected instanceof ExprDsl.ObjectValue, "record adapter");
        equal("Jason", ((ExprDsl.ObjectValue) reflected).get("name").toString(), "record field adaptation");
        equal("Jason:50",
                ExprDsl.Compiler.compile("person.name + ':' + person.age", functions)
                        .eval(new ExprDsl.MapEvalContext(
                                Map.of("person", new Person("Jason", 50)),
                                functions,
                                ExprDsl.EvaluationPolicy.DEFAULT,
                                ExprDsl.ValueAdapters.REFLECTIVE)),
                "reflective context adapter");

        try {
            ExprDsl.Json.write(ExprDsl.MissingValue.INSTANCE);
            throw new AssertionError("MISSING must not serialize");
        } catch (ExprDsl.EvaluationException expected) {
            equal("E_MISSING_JSON_VALUE", expected.code(), "missing JSON diagnostic");
        }

        try {
            ExprDsl.Json.write(new Object());
            throw new AssertionError("arbitrary Java objects must not serialize");
        } catch (ExprDsl.EvaluationException expected) {
            equal("E_NOT_JSON", expected.code(), "unsupported Java JSON diagnostic");
        }

        equal(List.of("B"),
                ExprDsl.Compiler.compile(
                                "map(filter([{name:'a',age:10},{name:'b',age:20}], u -> u.age >= 18), u -> upper(u.name))",
                                functions)
                        .eval(new ExprDsl.MapEvalContext(Map.of(), functions)),
                "collections remain Java-compatible at eval boundary");

        // Deterministic property-style differential checks without external test libraries.
        ExprDsl.CompiledExpression addition = ExprDsl.Compiler.compile(
                "a + b",
                functions,
                ExprDsl.CompileOptions.DEFAULT.withSchemas(Map.of(
                        "a", ExprDsl.DslSchema.longType(),
                        "b", ExprDsl.DslSchema.longType())));
        Random random = new Random(8473921L);
        for (int i = 0; i < 1_000; i++) {
            long a = random.nextInt(2_000_001) - 1_000_000L;
            long b = random.nextInt(2_000_001) - 1_000_000L;
            equal(a + b, addition.eval(new ExprDsl.MapEvalContext(
                    Map.of("a", a, "b", b), functions)), "random long addition " + i);
        }

        for (int i = 0; i < 250; i++) {
            ExprDsl.Value generated = randomJsonValue(random, 0);
            String encoded = ExprDsl.Json.write(generated);
            ExprDsl.Value decoded = ExprDsl.Json.parse(encoded);
            equal(encoded, ExprDsl.Json.write(decoded), "random JSON round trip " + i);
        }

        System.out.println("ExprDsl value/schema tests passed");
    }
}
