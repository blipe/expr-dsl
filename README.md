# Expr DSL

A JDK 21-only, JSON-first expression language that compiles expressions and lambdas to JVM bytecode. Function calls, property access, and index access use JVM `invokedynamic` with bounded polymorphic inline caches.

## Maven

```bash
mvn clean verify
```

The runtime has no external dependencies. JUnit 5 is used only for release verification.

```xml
<dependency>
  <groupId>dev.blipe</groupId>
  <artifactId>expr-dsl</artifactId>
  <version>0.9.0-SNAPSHOT</version>
</dependency>
```

## Example

```java
import dev.blipe.exprdsl.ExprDsl;

import java.util.Map;

ExprDsl.FunctionRegistry functions = ExprDsl.FunctionRegistry.standard();
ExprDsl.CompiledExpression expression = ExprDsl.Compiler.compile(
    "map(filter(users, u -> u.age >= 18), u -> upper(u.name))",
    functions
);

Object result = expression.eval(new ExprDsl.MapEvalContext(
    Map.of("users", java.util.List.of(
        Map.of("name", "Ada", "age", 17),
        Map.of("name", "Grace", "age", 22)
    )),
    functions
));

System.out.println(result); // [GRACE]
```

## Language and runtime

- JSON-native values with distinct `null` and `MISSING`
- list and object literals
- property and index access, safe access, and `??`
- compiled single- and multi-argument lambdas
- typed function registry, overloads, and varargs
- collection functions including `map`, `filter`, `reduce`, `flatMap`, `groupBy`, `sortBy`, and `sum`
- schema-aware semantic diagnostics
- configurable execution, null, missing-property, and numeric policies
- hidden generated classes and bounded weak compilation cache
- `invokedynamic` specialization for functions, properties, and indexes

## Verification coverage

The Maven test runs the complete release verification suite, including:

- JVM bytecode verification and `javap` inspection
- registry replacement and inline-cache relinking
- bounded polymorphic inline caches and megamorphic fallback
- JSON round trips and randomized arithmetic checks
- nested and multi-parameter compiled lambdas
- schema, null, and missing-value semantics
- 100,000 repeated evaluations
- 1,000 unique hidden-class compilations and cache lifecycle checks
- concurrent compile, link, and evaluation tests
