package io.github.tke.shush.gradle

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.TestSuiteScope
import de.infix.testBalloon.framework.shared.TestElementName
import de.infix.testBalloon.framework.shared.TestRegistering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Registers a test whose blocking [action] runs on [Dispatchers.IO], interrupted if the test is cancelled.
 * [action] can't suspend.
 */
@TestRegistering
fun TestSuiteScope.testIO(
    @TestElementName name: String,
    testConfig: TestConfig = TestConfig,
    action: () -> Unit,
) = test(name, testConfig) {
    runInterruptible(Dispatchers.IO) { action() }
}
