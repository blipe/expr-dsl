package dev.blipe.exprdsl;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExprDslHardeningTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static ExprDsl.CompileOptions strictFunctions() {
        return new ExprDsl.CompileOptions(
                Map.of(), false, true,
                ExprDsl.ExecutionLimits.DEFAULT,
                ExprDsl.EvaluationPolicy.DEFAULT,
                true);
    }

    private static void testHiddenClassAndCache() throws Throwable {
        ExprDsl.Compiler.clearCache();
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard();
        long compilations = ExprDsl.Compiler.stats().compilations();
        ExprDsl.CompiledExpression first = ExprDsl.Compiler.compile("x + 1", functions);
        ExprDsl.CompiledExpression second = ExprDsl.Compiler.compile("x + 1", functions);
        check(first == second, "same cache key returns same live expression");
        check(((ExprDsl.CompiledExpressionImpl) first).hiddenClass(),
                "compiled implementation must be a hidden class");
        equal(compilations + 1, ExprDsl.Compiler.stats().compilations(),
                "one compilation for one cache key");
        check(ExprDsl.Compiler.stats().cacheHits() >= 1, "cache hit recorded");
        equal(2L, first.eval(new ExprDsl.MapEvalContext(Map.of("x", 1L), functions)),
                "cached expression evaluates");

        functions.register("touch", 0, args -> "changed");
        ExprDsl.CompiledExpression afterVersionChange =
                ExprDsl.Compiler.compile("x + 1", functions);
        check(afterVersionChange != first,
                "registry version is part of the compilation cache key");
    }

    private static void testConcurrentSingleCompilation() throws Exception {
        ExprDsl.Compiler.clearCache();
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard();
        long before = ExprDsl.Compiler.stats().compilations();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<ExprDsl.CompiledExpression>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                tasks.add(() -> ExprDsl.Compiler.compile("value * 2", functions));
            }
            List<Future<ExprDsl.CompiledExpression>> futures = pool.invokeAll(tasks);
            Set<ExprDsl.CompiledExpression> identities =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Future<ExprDsl.CompiledExpression> future : futures) identities.add(future.get());
            equal(1, identities.size(), "concurrent compiles converge on one cached expression");
            equal(before + 1, ExprDsl.Compiler.stats().compilations(),
                    "concurrent same-key compilation happens once");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void testBoundedWeakCacheAndUnloadingEligibility() throws Exception {
        ExprDsl.Compiler.clearCache();
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard();
        for (int i = 0; i < 1_000; i++) {
            ExprDsl.Compiler.compile("x + " + i, functions);
        }
        check(ExprDsl.Compiler.stats().cacheEntries() <= 256,
                "compilation cache is bounded");

        WeakReference<?>[] references = createWeakReferences(functions);
        ExprDsl.Compiler.clearCache();
        awaitCollection(references);
        check(references[0].get() == null, "compiled expression became collectible");
        check(references[1].get() == null, "hidden implementation class became collectible");
    }

    private static WeakReference<?>[] createWeakReferences(ExprDsl.FunctionRegistry functions) {
        ExprDsl.CompiledExpression expression =
                ExprDsl.Compiler.compile("temporary + 918273", functions);
        Class<?> implementation =
                ((ExprDsl.CompiledExpressionImpl) expression).implementationClass();
        return new WeakReference<?>[]{
                new WeakReference<>(expression),
                new WeakReference<>(implementation)
        };
    }

    private static void awaitCollection(WeakReference<?>[] references) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (references[0].get() == null && references[1].get() == null) return;
            System.gc();
            // Modest pressure encourages a full collection without making the test memory hungry.
            byte[][] pressure = new byte[16][];
            for (int i = 0; i < pressure.length; i++) pressure[i] = new byte[256 * 1024];
            Thread.sleep(10L);
        }
    }

    private static void testStaticOverloadResolution() {
        ExprDsl.FunctionRegistry functions = new ExprDsl.FunctionRegistry()
                .register("typed", List.of(ExprDsl.DslType.STRING),
                        ExprDsl.DslType.STRING, true, args -> args[0])
                .register("typed", List.of(ExprDsl.DslType.NUMBER),
                        ExprDsl.DslType.LONG, true, args -> 1L);

        equal(ExprDsl.DslType.STRING,
                ExprDsl.Compiler.analyze("typed('x')", functions, strictFunctions()).resultType(),
                "string overload selected statically");
        equal(ExprDsl.DslType.LONG,
                ExprDsl.Compiler.analyze("typed(1)", functions, strictFunctions()).resultType(),
                "numeric overload selected statically");
        expectDiagnostic("S_NO_MATCHING_OVERLOAD",
                () -> ExprDsl.Compiler.compile("typed(true)", functions, strictFunctions()));
        expectDiagnostic("S_TYPE",
                () -> ExprDsl.Compiler.compile("sum('not an array')",
                        ExprDsl.FunctionRegistry.standard(), strictFunctions()));
        expectDiagnostic("S_TYPE",
                () -> ExprDsl.Compiler.compile("sortBy([1,2], 42)",
                        ExprDsl.FunctionRegistry.standard(), strictFunctions()));
        expectDiagnostic("S_LAMBDA_ARITY",
                () -> ExprDsl.Compiler.compile("reduce([1,2], 0, value -> value)",
                        ExprDsl.FunctionRegistry.standard(), strictFunctions()));
    }

    private static void testOptimizer() throws Throwable {
        AtomicInteger compileTimeCalls = new AtomicInteger();
        ExprDsl.FunctionRegistry functions = new ExprDsl.FunctionRegistry()
                .register("constant", List.of(ExprDsl.DslType.STRING),
                        ExprDsl.DslType.STRING, true, args -> {
                            compileTimeCalls.incrementAndGet();
                            return "folded:" + args[0];
                        });
        ExprDsl.CompiledExpression expression =
                ExprDsl.Compiler.compile("constant('x')", functions, strictFunctions());
        equal(1, compileTimeCalls.get(), "pure constant function folded once at compile time");
        equal("folded:x", expression.eval(new ExprDsl.MapEvalContext(Map.of(), functions)),
                "folded function result");
        equal("folded:x", expression.eval(new ExprDsl.MapEvalContext(Map.of(), functions)),
                "folded function remains constant");
        equal(1, compileTimeCalls.get(), "folded function not called at runtime");

        ExprDsl.CompiledExpression deadBranch = ExprDsl.Compiler.compile(
                "if(false, unknownFunction(), 7)", functions, strictFunctions());
        equal(7L, deadBranch.eval(new ExprDsl.MapEvalContext(Map.of(), functions)),
                "dead branch removed before strict semantic resolution");
        equal(7L, ExprDsl.Compiler.compile("({answer: 7}).answer", functions)
                        .eval(new ExprDsl.MapEvalContext(Map.of(), functions)),
                "constant property access folded");
        equal(2L, ExprDsl.Compiler.compile("[1,2,3][1]", functions)
                        .eval(new ExprDsl.MapEvalContext(Map.of(), functions)),
                "constant index access folded");
    }

    private static void testComputedStackBeyondOldFixedLimit() throws Throwable {
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard();
        String expression = "x";
        for (int i = 0; i < 180; i++) expression = "max(x," + expression + ")";
        ExprDsl.CompiledExpression compiled = ExprDsl.Compiler.compile(expression, functions);
        equal(9L, compiled.eval(new ExprDsl.MapEvalContext(Map.of("x", 9L), functions)),
                "deep expression verifies and evaluates with computed stack size");
    }


    private static void testSteadyStateEvaluation() throws Throwable {
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard();
        ExprDsl.CompiledExpression expression = ExprDsl.Compiler.compile(
                "(a * 2) + (b / 2)", functions);
        ExprDsl.MapEvalContext context = new ExprDsl.MapEvalContext(
                Map.of("a", 10L, "b", 8L), functions);
        for (int i = 0; i < 100_000; i++) {
            equal(24L, expression.eval(context), "steady-state result " + i);
        }
    }

    private static void testDeterministicBytecode() {
        String source = "upper(user.name) + ':' + sum(user.values)";
        ExprDsl.Expr firstAst = ExprDsl.Parser.parse(source);
        ExprDsl.Expr secondAst = ExprDsl.Parser.parse(source);
        byte[] first = new ExprDsl.ClassEmitter("DeterministicProbe").emit(firstAst);
        byte[] second = new ExprDsl.ClassEmitter("DeterministicProbe").emit(secondAst);
        check(java.util.Arrays.equals(first, second),
                "same AST and class name produce deterministic bytecode");
    }

    private static void testClassFileLimitDiagnostics() {
        StringBuilder source = new StringBuilder("[");
        for (int i = 0; i < 12_000; i++) {
            if (i > 0) source.append(',');
            source.append('0');
        }
        source.append(']');
        expectDiagnostic("S_METHOD_SIZE",
                () -> ExprDsl.Compiler.compile(source.toString(),
                        ExprDsl.FunctionRegistry.standard()));
    }

    private static void expectDiagnostic(String code, ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected diagnostic " + code);
        } catch (ExprDsl.CompileException expected) {
            check(expected.diagnostics().stream().anyMatch(d -> d.code().equals(code)),
                    "expected diagnostic " + code + ", got " + expected.diagnostics());
        } catch (Throwable unexpected) {
            throw new AssertionError("expected CompileException " + code, unexpected);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Throwable; }

    public static void main(String[] args) throws Throwable {
        testHiddenClassAndCache();
        testConcurrentSingleCompilation();
        testBoundedWeakCacheAndUnloadingEligibility();
        testStaticOverloadResolution();
        testOptimizer();
        testComputedStackBeyondOldFixedLimit();
        testSteadyStateEvaluation();
        testDeterministicBytecode();
        testClassFileLimitDiagnostics();
        System.out.println("ExprDsl hardening tests passed");
    }
}
