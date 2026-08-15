package dev.blipe.exprdsl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ExprDslBytecodeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        for (int at = 0; (at = source.indexOf(token, at)) >= 0; at += token.length()) count++;
        return count;
    }

    public static void main(String[] args) throws Exception {
        ExprDsl.Expr ast = ExprDsl.Parser.parse("upper(user.name) + user['suffix']");
        byte[] bytes = new ExprDsl.ClassEmitter("IndyAccessProbe").emit(ast);
        Path directory = Path.of("build", "indy");
        Files.createDirectories(directory);
        Files.write(directory.resolve("IndyAccessProbe.class"), bytes);

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "javap").toString(),
                "-c", "-v", directory.resolve("IndyAccessProbe.class").toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "javap failed:\n" + output);
        check(occurrences(output, "invokedynamic") >= 3,
                "expected function, property, and index invokedynamic instructions:\n" + output);
        check(output.contains("bootstrapProperty"), "missing property bootstrap");
        check(output.contains("bootstrapIndex"), "missing index bootstrap");
        check(output.contains("ExprDsl$IndyBootstrap.bootstrap"), "missing function bootstrap");
        System.out.println("ExprDsl bytecode tests passed");
    }
}
