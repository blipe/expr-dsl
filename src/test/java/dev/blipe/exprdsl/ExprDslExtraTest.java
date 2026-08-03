package dev.blipe.exprdsl;

import java.util.*;
import java.util.concurrent.*;

public final class ExprDslExtraTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object eval(String source, Map<String,Object> vars,
                               ExprDsl.FunctionRegistry registry) throws Throwable {
        return ExprDsl.Compiler.compile(source, registry)
                .eval(new ExprDsl.MapEvalContext(vars, registry));
    }

    public static void main(String[] args) throws Throwable {
        ExprDsl.FunctionRegistry standard = ExprDsl.FunctionRegistry.standard();

        Object nested = eval(
                "any([[1,2],[3,4]], xs -> any(xs, x -> x == 4))",
                Map.of(), standard);
        check(Boolean.TRUE.equals(nested), "nested compiled lambdas");

        standard.registerContext(
                "apply2",
                List.of(ExprDsl.DslType.ANY, ExprDsl.DslType.ANY, ExprDsl.DslType.LAMBDA),
                ExprDsl.DslType.ANY,
                true,
                (ctx, a) -> ((ExprDsl.DslLambda) a[2]).apply(a[0], a[1]));
        Object apply2 = eval("apply2(2, 3, (a,b) -> a + b)", Map.of(), standard);
        check(Long.valueOf(5).equals(apply2), "multi-argument lambda: " + apply2);

        standard.registerVarargs(
                "join",
                List.of(ExprDsl.DslType.STRING),
                ExprDsl.DslType.STRING,
                ExprDsl.DslType.STRING,
                true,
                a -> String.join("-", Arrays.stream(a).map(String::valueOf).toList()));
        Object joined = eval("join('a','b','c')", Map.of(), standard);
        check("a-b-c".equals(joined), "varargs: " + joined);

        ExprDsl.CompileOptions strictFunctions = new ExprDsl.CompileOptions(
                Map.of(), false, true, ExprDsl.ExecutionLimits.DEFAULT,
                ExprDsl.EvaluationPolicy.DEFAULT, true);
        try {
            ExprDsl.Compiler.analyze("missing(1)", standard, strictFunctions);
            throw new AssertionError("unknown function should fail semantic analysis");
        } catch (ExprDsl.CompileException expected) {
            check(expected.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("S_UNKNOWN_FUNCTION")),
                    "unknown function diagnostic");
        }

        ExprDsl.EvaluationPolicy errorPolicy = new ExprDsl.EvaluationPolicy(
                ExprDsl.MissingPropertyMode.ERROR);
        ExprDsl.CompileOptions missingPropertyOptions = new ExprDsl.CompileOptions(
                Map.of(), false, false, ExprDsl.ExecutionLimits.DEFAULT,
                errorPolicy, true);
        ExprDsl.CompiledExpression missing = ExprDsl.Compiler.compile(
                "obj.missing", standard, missingPropertyOptions);
        try {
            missing.eval(new ExprDsl.MapEvalContext(
                    Map.of("obj", Map.of("present", 1L)), standard, errorPolicy));
            throw new AssertionError("missing property should fail");
        } catch (ExprDsl.EvaluationException expected) {
            check("E_MISSING_PROPERTY".equals(expected.code()), "missing property code");
        }
        Object safe = ExprDsl.Compiler.compile("obj?.missing", standard, missingPropertyOptions)
                .eval(new ExprDsl.MapEvalContext(Map.of("obj", Map.of()), standard, errorPolicy));
        check(safe == null, "safe access");

        Object safeIndex = ExprDsl.Compiler.compile("obj?['missing']", standard, missingPropertyOptions)
                .eval(new ExprDsl.MapEvalContext(Map.of("obj", Map.of()), standard, errorPolicy));
        check(safeIndex == null, "safe index");

        Object coalesced = eval("value ?? 'fallback'", Collections.singletonMap("value", null), standard);
        check("fallback".equals(coalesced), "coalesce");
        Object branch = eval("if(flag, 1, 2)", Map.of("flag", true), standard);
        check(Long.valueOf(1).equals(branch), "dynamic if");

        ExprDsl.ExecutionLimits tiny = new ExprDsl.ExecutionLimits(
                1024, 32, 4, 100, 100, 32, 2);
        ExprDsl.CompileOptions limitedOptions = new ExprDsl.CompileOptions(
                Map.of(), false, false, tiny, ExprDsl.EvaluationPolicy.DEFAULT, false);
        ExprDsl.CompiledExpression limited = ExprDsl.Compiler.compile(
                "1 + 2 + 3 + 4", standard, limitedOptions);
        try {
            limited.eval(new ExprDsl.MapEvalContext(Map.of(), standard));
            throw new AssertionError("operation budget should fail");
        } catch (ExprDsl.EvaluationException expected) {
            check("E_OPERATION_LIMIT".equals(expected.code()), "operation limit code");
        }

        ExprDsl.FunctionRegistry dynamic = new ExprDsl.FunctionRegistry();
        dynamic.register("kind", List.of(ExprDsl.DslType.STRING), ExprDsl.DslType.STRING,
                true, a -> "string");
        dynamic.register("kind", List.of(ExprDsl.DslType.NUMBER), ExprDsl.DslType.STRING,
                true, a -> "number");
        ExprDsl.CompiledExpression dynamicCall = ExprDsl.Compiler.compile("kind(v)");
        Object stringKind = dynamicCall.eval(new ExprDsl.MapEvalContext(Map.of("v", "x"), dynamic));
        Object numberKind = dynamicCall.eval(new ExprDsl.MapEvalContext(Map.of("v", 10L), dynamic));
        check("string".equals(stringKind) && "number".equals(numberKind),
                "runtime overload relinking");

        ExprDsl.FunctionRegistry mutable = new ExprDsl.FunctionRegistry()
                .register("f", 0, a -> "old");
        ExprDsl.CompiledExpression mutableCall = ExprDsl.Compiler.compile("f()", mutable);
        check("old".equals(mutableCall.eval(new ExprDsl.MapEvalContext(Map.of(), mutable))),
                "initial registry function");
        mutable.register("f", 0, a -> "new");
        check("new".equals(mutableCall.eval(new ExprDsl.MapEvalContext(Map.of(), mutable))),
                "registry version relink");

        Object json = eval("fromJson(toJson({name:'jason', values:[1,2,3]})).values[2]",
                Map.of(), standard);
        check(Long.valueOf(3).equals(json), "JSON round trip: " + json);

        ExprDsl.CompiledExpression concurrent = ExprDsl.Compiler.compile(
                "map(values, x -> x * factor)", standard);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                long factor = i + 1L;
                futures.add(pool.submit(() -> {
                    try {
                        return concurrent.eval(new ExprDsl.MapEvalContext(
                                Map.of("values", List.of(1L,2L,3L), "factor", factor), standard));
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                long factor = i + 1L;
                check(futures.get(i).get().equals(List.of(factor, 2L*factor, 3L*factor)),
                        "concurrent evaluation " + i);
            }
        } finally {
            pool.shutdownNow();
        }

        System.out.println("ExprDsl extra tests passed");
    }
}
