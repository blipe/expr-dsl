package dev.blipe.exprdsl;

import org.junit.jupiter.api.Test;

final class ExprDslMavenTest {
    @Test
    void completeReleaseVerification() throws Throwable {
        ExprDsl.main(new String[0]);
        ExprDslExtraTest.main(new String[0]);
        ExprDslValueTest.main(new String[0]);
        ExprDslIndyCollectionTest.main(new String[0]);
        ExprDslBytecodeTest.main(new String[0]);
        ExprDslHardeningTest.main(new String[0]);
    }
}
