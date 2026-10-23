package io.github.tke.shush.runners

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.TestSuite
import de.infix.testBalloon.framework.core.TestSuiteScope
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.shared.TestRegistering
import de.infix.testBalloon.framework.shared.TestSuitePropertyName
import io.github.tke.shush.testIO
import java.io.File
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerTest
import org.jetbrains.kotlin.test.services.KotlinTestInfo

val JvmDiagnosticTest by testDataSuite("diagnostics") { AbstractJvmDiagnosticTest() }

val JvmBoxTest by testDataSuite("box") { AbstractJvmBoxTest() }

private val testDataRoot = File(System.getProperty("shush.test.testData") ?: error("shush.test.testData is not set"))

/**
 * A test per `.kt` file in [directory] of the test data, each run by a new [runner], and a suite per subdirectory.
 * Tests block on compilations, so they run in real time and without a timeout.
 */
@TestRegistering
private fun testDataSuite(
    directory: String,
    @TestSuitePropertyName qualifiedPropertyName: String = "",
    runner: () -> AbstractKotlinCompilerTest,
): Lazy<TestSuite> = testSuite(
    qualifiedPropertyName = qualifiedPropertyName,
    testConfig = TestConfig.testScope(isEnabled = false),
) {
    testDataTests(testDataRoot.resolve(directory), runner)
}

private fun TestSuiteScope.testDataTests(directory: File, runner: () -> AbstractKotlinCompilerTest) {
    val files = directory.listFiles()?.sortedBy { it.name } ?: error("$directory is not a directory")
    for (file in files) {
        when {
            file.isDirectory -> testSuite(file.name) { testDataTests(file, runner) }
            file.extension == "kt" -> testIO(file.nameWithoutExtension) {
                runner().apply {
                    initTestInfo(
                        KotlinTestInfo(
                            className = this::class.java.name,
                            methodName = "test" + file.nameWithoutExtension.replaceFirstChar { it.uppercase() },
                            tags = emptySet(),
                        )
                    )
                    runTest(file.path)
                }
            }
        }
    }
}
