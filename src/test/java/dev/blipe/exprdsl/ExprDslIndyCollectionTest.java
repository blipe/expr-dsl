package dev.blipe.exprdsl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ExprDslIndyCollectionTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static Object eval(String source, ExprDsl.FunctionRegistry functions) throws Throwable {
        return ExprDsl.Compiler.compile(source, functions)
                .eval(new ExprDsl.MapEvalContext(Map.of(), functions));
    }

    private static void testDirectFunctionLinking() throws Throwable {
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard()
                .register("identity", 1, args -> args[0]);
        ExprDsl.CompiledExpression expression = ExprDsl.Compiler.compile("identity(x)", functions);

        ExprDsl.IndyBootstrap.resetStatistics();
        long before = functions.resolutionCount();
        ExprDsl.MapEvalContext context = new ExprDsl.MapEvalContext(Map.of("x", 7L), functions);
        equal(7L, expression.eval(context), "first identity result");
        equal(7L, expression.eval(context), "warm identity result");
        equal(1L, functions.resolutionCount() - before,
                "monomorphic function profile resolves once");
        equal(1L, ExprDsl.IndyBootstrap.statistics().functionLinks(),
                "one direct function link");

        functions.register("identity", 1, args -> "new:" + args[0]);
        equal("new:7", expression.eval(context), "registry replacement relinks");
        equal(2L, functions.resolutionCount() - before,
                "replacement causes one new resolution");
    }

    private static void testBoundedFunctionPic() throws Throwable {
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard()
                .register("identity", 1, args -> args[0]);
        ExprDsl.CompiledExpression expression = ExprDsl.Compiler.compile("identity(x)", functions);
        Object[] profiles = {
                1L,
                1.5d,
                new BigDecimal("2.50"),
                "text",
                true,
                List.of(1L),
                Map.of("x", 1L)
        };

        ExprDsl.IndyBootstrap.resetStatistics();
        for (Object profile : profiles) {
            equal(profile, expression.eval(new ExprDsl.MapEvalContext(
                    Map.of("x", profile), functions)), "profile result " + profile);
        }
        ExprDsl.IndyBootstrap.Statistics stats = ExprDsl.IndyBootstrap.statistics();
        equal(5L, stats.functionLinks(), "PIC is bounded at five profiles");
        equal(1L, stats.functionMegamorphic(), "sixth profile becomes megamorphic");
    }

    private static void testConcurrentRelinking() throws Throwable {
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard()
                .register("identity", 1, args -> args[0]);
        ExprDsl.CompiledExpression expression = ExprDsl.Compiler.compile("identity(x)", functions);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            ArrayList<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int n = i;
                tasks.add(() -> {
                    Object value = switch (n % 5) {
                        case 0 -> (long) n;
                        case 1 -> (double) n;
                        case 2 -> "v" + n;
                        case 3 -> n % 2 == 0;
                        default -> List.of((long) n);
                    };
                    try {
                        return expression.eval(new ExprDsl.MapEvalContext(
                                Map.of("x", value), functions));
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
            }
            List<Future<Object>> futures = pool.invokeAll(tasks);
            for (Future<Object> future : futures) check(future.get() != null,
                    "concurrent evaluation result");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void testAccessInlineCaches() throws Throwable {
        ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard();
        ExprDsl.CompiledExpression property = ExprDsl.Compiler.compile("user.name", functions);
        ExprDsl.CompiledExpression index = ExprDsl.Compiler.compile("value[key]", functions);

        ExprDsl.IndyBootstrap.resetStatistics();
        ExprDsl.MapEvalContext propertyContext = new ExprDsl.MapEvalContext(
                Map.of("user", Map.of("name", "Jason")), functions);
        equal("Jason", property.eval(propertyContext), "property first result");
        equal("Jason", property.eval(propertyContext), "property warm result");
        equal(1L, ExprDsl.IndyBootstrap.statistics().propertyLinks(),
                "property call site links once");

        equal(20L, index.eval(new ExprDsl.MapEvalContext(
                Map.of("value", List.of(10L, 20L), "key", 1L), functions)),
                "array index");
        equal("b", index.eval(new ExprDsl.MapEvalContext(
                Map.of("value", Map.of("a", "b"), "key", "a"), functions)),
                "object index");
        equal("b", index.eval(new ExprDsl.MapEvalContext(
                Map.of("value", "abc", "key", 1L), functions)),
                "string index");
        equal(3L, ExprDsl.IndyBootstrap.statistics().indexLinks(),
                "index profiles specialize independently");
    }

    private static void testCollections() throws Throwable {
        ExprDsl.FunctionRegistry f = ExprDsl.FunctionRegistry.standard();
        equal(10L, eval("reduce([1,2,3,4], 0, (total, value) -> total + value)", f),
                "reduce");
        equal(List.of(1L, 2L, 3L, 4L),
                eval("flatMap([[1,2],[3,4]], values -> values)", f), "flatMap");
        equal(Map.of("a", List.of(
                        Map.of("kind", "a", "value", 1L),
                        Map.of("kind", "a", "value", 3L)),
                    "b", List.of(Map.of("kind", "b", "value", 2L))),
                eval("groupBy([{kind:'a',value:1},{kind:'b',value:2},{kind:'a',value:3}], x -> x.kind)", f),
                "groupBy");
        equal(List.of("a", "b", "c"),
                eval("map(sortBy([{name:'c',n:3},{name:'a',n:1},{name:'b',n:2}], x -> x.n), x -> x.name)", f),
                "sortBy");
        equal(6L, eval("sum([1,2,3])", f), "sum");
        equal(12L, eval("sum([{n:2},{n:4},{n:6}], x -> x.n)", f), "sum mapper");
        equal(new BigDecimal("1.5"), eval("average([1,2])", f), "average exact");
        equal(List.of(1L, 2L, 3L), eval("distinct([1,2,1,3,2])", f), "distinct");
        equal(List.of(1L, 2L), eval("take([1,2,3,4], 2)", f), "take");
        equal(List.of(3L, 4L), eval("skip([1,2,3,4], 2)", f), "skip");
        equal(List.of(1L, 2L, 3L), eval("flatten([[1],[2,3]])", f), "flatten");
        equal("b", eval("maxBy([{name:'a',n:1},{name:'b',n:4}], x -> x.n).name", f),
                "maxBy");
        equal("a", eval("minBy([{name:'a',n:1},{name:'b',n:4}], x -> x.n).name", f),
                "minBy");
        equal(Map.of("a", Map.of("name", "a", "n", 2L),
                        "b", Map.of("name", "b", "n", 3L)),
                eval("associateBy([{name:'a',n:1},{name:'a',n:2},{name:'b',n:3}], x -> x.name)", f),
                "associateBy last value wins");
        equal(3L, eval("count([1,2,3])", f), "count array");
        equal(3L, eval("last([1,2,3])", f), "last");
    }

    public static void main(String[] args) throws Throwable {
        testDirectFunctionLinking();
        testBoundedFunctionPic();
        testConcurrentRelinking();
        testAccessInlineCaches();
        testCollections();
        System.out.println("ExprDsl indy/collection tests passed");
    }
}
