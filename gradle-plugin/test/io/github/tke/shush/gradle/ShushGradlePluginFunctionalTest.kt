package io.github.tke.shush.gradle

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.TestSuiteScope
import de.infix.testBalloon.framework.core.aroundEachTest
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.shared.TestRegistering
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.withTimeout
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Runs real builds of Kotlin JVM projects, resolving this Gradle plugin and the compiler plugin from the build-local
 * repository the `test` task publishes to. Scenarios run against the oldest supported and the current Gradle version
 * and every Kotlin version in `shush.kotlinVersions`: the published compiler plugin is a single jar for all supported
 * compilers.
 *
 * Tests block on Gradle builds, so they run in real time. A cold first build (Gradle distribution download, daemon
 * start, dependency resolution) can take over a minute; the timeout only catches hangs.
 */
val ShushGradlePluginFunctionalTest by testSuite(
    testConfig = TestConfig
        .testScope(isEnabled = false)
        .aroundEachTest { withTimeout(5.minutes) { it() } },
) {
    testSuite("mokkery support") {
        scenario(
            "mokkery preset is enabled by convention, keeps genuine warnings, tests pass and configuration cache is reused",
            matrix,
        ) { gradle, kotlin ->
            writeMokkeryProject(kotlin, applyShush = true)

            val first = runner(gradle, projectDir, "test", "--configuration-cache").build()
            assertEquals(TaskOutcome.SUCCESS, first.task(":test")?.outcome)
            assertEquals(mokkeryLines(GENUINE, CUSTOM), unusedValueWarningLines(first), first.output)
            assertMokkeryTestsPassed()

            // Same invocation with the outputs removed: the compiler plugin options must survive the configuration cache.
            assertTrue(projectDir.resolve("build").deleteRecursively())
            val second = runner(gradle, projectDir, "test", "--configuration-cache").build()
            assertContains(second.output, "Reusing configuration cache.")
            assertEquals(TaskOutcome.SUCCESS, second.task(":compileTestKotlin")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, second.task(":test")?.outcome)
            assertEquals(mokkeryLines(GENUINE, CUSTOM), unusedValueWarningLines(second), second.output)
            assertMokkeryTestsPassed()
        }

        scenario("without the plugin Mokkery template warnings are reported") { gradle, kotlin ->
            writeMokkeryProject(kotlin, applyShush = false)

            val result = runner(gradle, projectDir, "compileTestKotlin").build()
            assertEquals(mokkeryLines(GENUINE, CUSTOM, TEMPLATE), unusedValueWarningLines(result), result.output)
        }

        scenario("mokkery set to false disables the preset") { gradle, kotlin ->
            writeMokkeryProject(kotlin, applyShush = true, shush = "mokkery.set(false)")

            val result = runner(gradle, projectDir, "compileTestKotlin", "--configuration-cache").build()
            assertEquals(mokkeryLines(GENUINE, CUSTOM, TEMPLATE), unusedValueWarningLines(result), result.output)
        }

        scenario("custom call rules combine with the mokkery preset") { gradle, kotlin ->
            writeMokkeryProject(
                kotlin,
                applyShush = true,
                shush = """
                    mokkery()
                    rule("my-templates") {
                        diagnostics.add("RETURN_VALUE_NOT_USED")
                        diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
                        callLambdas.add("com.example.myTemplate")
                    }
                """,
            )

            val result = runner(gradle, projectDir, "test", "--configuration-cache").build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
            assertEquals(mokkeryLines(GENUINE), unusedValueWarningLines(result), result.output)
            assertMokkeryTestsPassed()
        }
    }

    testSuite("custom rules") {
        scenario("files rule suppresses warnings in generated sources only") { gradle, kotlin ->
            writeGeneratedCodeProject(projectDir, kotlin)

            val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
            assertEquals(expectedWarnings(GENERATED_SOURCES + HANDWRITTEN_SOURCES), warnings(result), result.output)
        }

        // backs the isolated projects compatibility declared on the Plugin Portal
        scenario("builds with isolated projects", currentGradleMatrix) { gradle, kotlin ->
            writeGeneratedCodeProject(projectDir, kotlin)
            projectDir.resolve("settings.gradle.kts").appendText("include(\"sub\")\n")
            projectDir.resolve("sub/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
                """
                plugins {
                    kotlin("jvm")
                    id("io.github.tke.shush")
                }

                // fail rather than pass without isolated projects, such as if the flag is renamed
                abstract class Features @Inject constructor(val features: BuildFeatures)
                check(objects.newInstance<Features>().features.isolatedProjects.active.get()) { "isolated projects is off" }

                shush {
                    rule("sub") {
                        diagnostics.add("RETURN_VALUE_NOT_USED")
                        files.add("src/**")
                    }
                }
                """.trimIndent() + "\n"
            )
            projectDir.resolve("sub/src/main/kotlin/Sub.kt").apply { parentFile.mkdirs() }.writeText("fun sub() = 1\n")

            val result = runner(gradle, projectDir, "compileKotlin", "-Dorg.gradle.unsafe.isolated-projects=true").build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":sub:compileKotlin")?.outcome)
        }

        scenario("without rules generated sources are reported") { gradle, kotlin ->
            writeGeneratedCodeProject(projectDir, kotlin, shush = "")

            val result = runner(gradle, projectDir, "compileKotlin").build()
            val all = warnings(result)
            for (file in GENERATED_SOURCES.keys.map { it.substringAfterLast('/') }) {
                assertTrue(all[file].orEmpty().isNotEmpty(), "expected warnings in $file:\n${result.output}")
            }
            assertEquals(
                expectedWarnings(HANDWRITTEN_SOURCES).getValue("Handwritten.kt"),
                all["Handwritten.kt"],
                result.output
            )
            // Its own @file:Suppress("UNUSED_VARIABLE") hides line 6.
            assertEquals(setOf(5, 7), all["AlreadySuppressed.kt"], result.output)
        }

        scenario(
            "annotatedDeclarations and annotatedCalls rules suppress within annotated code",
        ) { gradle, kotlin ->
            writeProject(
                projectDir,
                kotlin,
                shush = """
                    rule("legacy") {
                        diagnostics.add("REDUNDANT_VISIBILITY_MODIFIER")
                        diagnostics.add("RETURN_VALUE_NOT_USED")
                        annotatedDeclarations.add("com.example.Legacy")
                    }
                    rule("templates") {
                        diagnostics.add("RETURN_VALUE_NOT_USED")
                        diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
                        annotatedCalls.add("com.example.Template")
                    }
                """,
                sources = ANNOTATED_SOURCES,
            )

            val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
            assertEquals(expectedWarnings(ANNOTATED_SOURCES), warnings(result), result.output)
        }

        scenario("calls rule suppresses on calls of the function") { gradle, kotlin ->
            writeProject(
                projectDir,
                kotlin,
                shush = """
                    rule("empty-responses") {
                        diagnostics.add("RETURN_VALUE_NOT_USED")
                        diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
                        calls.add("com.example.Stub.ping")
                    }
                """,
                sources = CALL_SOURCES,
            )

            val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
            assertEquals(expectedWarnings(CALL_SOURCES), warnings(result), result.output)
        }

        scenario("ignorable return types mark functions returning them") { gradle, kotlin ->
            writeProject(
                projectDir,
                kotlin,
                shush = """ignorableReturnTypes.add("com.example.Empty")""",
                sources = CALL_SOURCES,
            )

            val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
            assertEquals(expectedWarnings(CALL_SOURCES), warnings(result), result.output)
        }

        scenario("a rule with targets but no diagnostics fails the build", currentGradleMatrix) { gradle, kotlin ->
            writeGeneratedCodeProject(projectDir, kotlin, shush = """rule("broken") { files.add("build/generated/**") }""")

            val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").buildAndFail()
            assertContains(result.output, "shush rule 'broken' in project ':' has targets but no diagnostics")
        }

        scenario("compilation is relocatable through the build cache", currentGradleMatrix) { gradle, kotlin ->
            val cacheDir = tempDir.resolve("build-cache")
            val first = tempDir.resolve("first")
            val second = tempDir.resolve("second")
            writeGeneratedCodeProject(first, kotlin, buildCache = cacheDir)
            writeGeneratedCodeProject(second, kotlin, buildCache = cacheDir)

            val stored = runner(gradle, first, "compileKotlin", "--build-cache").build()
            assertEquals(TaskOutcome.SUCCESS, stored.task(":compileKotlin")?.outcome)
            val reused = runner(gradle, second, "compileKotlin", "--build-cache").build()
            assertEquals(TaskOutcome.FROM_CACHE, reused.task(":compileKotlin")?.outcome, reused.output)
        }
    }

    // Gradle plugin logic only: the compiler sees ordinary named rules, so one Kotlin version suffices
    testSuite("anonymous rules") {
        scenario("anonymous rules combine with named rules and the mokkery preset", currentMatrix) { gradle, kotlin ->
            writeMokkeryProject(
                kotlin,
                applyShush = true,
                shush = """
                    rule {
                        diagnostics.add("RETURN_VALUE_NOT_USED")
                        diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
                        callLambdas.add("com.example.myTemplate")
                    }
                    rule("unused-variables") {
                        diagnostics.add("UNUSED_VARIABLE")
                        files.add("src/test/kotlin/**")
                    }
                """,
            )

            val result = runner(gradle, projectDir, "test", "--configuration-cache").build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
            assertEquals(mokkeryLines(GENUINE), unusedValueWarningLines(result), result.output)
            assertMokkeryTestsPassed()
        }

        scenario(
            "distinct anonymous rules all apply and their declaration order does not affect the compile task",
            currentMatrix,
        ) { gradle, kotlin ->
            val legacy = """
                rule {
                    diagnostics.add("REDUNDANT_VISIBILITY_MODIFIER")
                    diagnostics.add("RETURN_VALUE_NOT_USED")
                    annotatedDeclarations.add("com.example.Legacy")
                }
            """.trimIndent()
            // Declared with its values in a different order the second time, and duplicated: identical rules collapse.
            val templates = listOf(
                """
                rule {
                    diagnostics.add("RETURN_VALUE_NOT_USED")
                    diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
                    annotatedCalls.add("com.example.Template")
                }
                """.trimIndent(),
                """
                rule {
                    annotatedCalls.add("com.example.Template")
                    diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
                    diagnostics.add("RETURN_VALUE_NOT_USED")
                }
                """.trimIndent(),
            )

            fun write(shush: String) = writeProject(projectDir, kotlin, shush = shush, sources = ANNOTATED_SOURCES)

            write(listOf(legacy, templates[0], templates[1]).joinToString("\n"))
            val first = runner(gradle, projectDir, "compileKotlin").build()
            assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome)
            assertEquals(expectedWarnings(ANNOTATED_SOURCES), warnings(first), first.output)

            // Same rules, swapped: the generated names and so the compile task's inputs are unchanged.
            write(listOf(templates[1], legacy).joinToString("\n"))
            val swapped = runner(gradle, projectDir, "compileKotlin").build()
            assertEquals(TaskOutcome.UP_TO_DATE, swapped.task(":compileKotlin")?.outcome, swapped.output)

            // Changing a rule's contents does change them.
            write(templates[0])
            val changed = runner(gradle, projectDir, "compileKotlin").build()
            assertEquals(TaskOutcome.SUCCESS, changed.task(":compileKotlin")?.outcome, changed.output)
        }

        scenario(
            "an anonymous rule with targets but no diagnostics fails the build without its generated name",
            currentMatrix,
        ) { gradle, kotlin ->
            writeGeneratedCodeProject(projectDir, kotlin, shush = """rule { files.add("build/generated/**") }""")

            val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").buildAndFail()
            assertContains(
                result.output,
                "anonymous shush rule (files: build/generated/**) in project ':' has targets but no diagnostics",
            )
            assertFalse(result.output.contains("anonymous-"), result.output)
        }

        scenario("named rules can't use the anonymous rule prefix", currentMatrix) { gradle, kotlin ->
            writeGeneratedCodeProject(
                projectDir,
                kotlin,
                shush = """
                    rule("anonymous-generated") {
                        diagnostics.add("REDUNDANT_VISIBILITY_MODIFIER")
                        files.add("build/generated/**")
                    }
                """,
            )

            val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").buildAndFail()
            assertContains(
                result.output,
                "shush rule 'anonymous-generated' in project ':' has an invalid name: the 'anonymous-' prefix is reserved for anonymous rules.",
            )
        }
    }
}

/** Registers a test per Gradle and Kotlin version pair in [matrix], each in a new temporary directory. */
@TestRegistering
private fun TestSuiteScope.scenario(
    name: String,
    matrix: List<Pair<String, String>> = io.github.tke.shush.gradle.matrix,
    action: Scenario.(gradle: String, kotlin: String) -> Unit,
) = testSuite(name) {
    for ((gradle, kotlin) in matrix) {
        testIO("Gradle $gradle, Kotlin $kotlin") {
            val tempDir = createTempDirectory("shush-functional-test").toFile()
            try {
                Scenario(tempDir).action(gradle, kotlin)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}

private class Scenario(val tempDir: File) {
    val projectDir: File get() = tempDir.resolve("project")

    fun runner(gradle: String, dir: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()
            .apply {
                if (gradle == CURRENT) withGradleInstallation(File(property("shush.currentGradleHome")))
                else withGradleVersion(gradle)
            }

    fun writeProject(
        dir: File,
        kotlin: String,
        applyShush: Boolean = true,
        mokkery: Boolean = false,
        extraWarnings: Boolean = true,
        buildCache: File? = null,
        buildScript: String = "",
        shush: String? = null,
        sources: Map<String, String>,
    ) {
        val repository = File(property("shush.repository")).toURI()
        dir.resolve("settings.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            pluginManagement {
                repositories {
                    maven("$repository")
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven("$repository")
                    mavenCentral()
                }
            }
            ${buildCache?.let { "buildCache { local { directory = file(\"${it.invariantSeparatorsPath}\") } }" } ?: ""}
            rootProject.name = "functional-test"
            """.trimIndent() + "\n"
        )
        dir.resolve("gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx2g
            kotlin.compiler.execution.strategy=in-process
            """.trimIndent() + "\n"
        )
        val plugins = listOfNotNull(
            """kotlin("jvm") version "$kotlin"""",
            if (mokkery) """id("dev.mokkery") version "${property("shush.mokkeryVersion")}"""" else null,
            if (applyShush) """id("io.github.tke.shush") version "${property("shush.pluginVersion")}"""" else null,
        )
        dir.resolve("build.gradle.kts").writeText(
            buildString {
                appendLine("plugins {")
                plugins.forEach { appendLine("    $it") }
                appendLine("}")
                appendLine()
                appendLine("kotlin {")
                appendLine("    compilerOptions {")
                appendLine("        freeCompilerArgs.add(\"-Xreturn-value-checker=full\")")
                if (extraWarnings) appendLine("        extraWarnings = true")
                appendLine("    }")
                appendLine("}")
                appendLine(buildScript.trimIndent())
                if (shush != null) {
                    appendLine()
                    appendLine("shush {")
                    shush.trimIndent().lines().forEach { appendLine("    $it") }
                    appendLine("}")
                }
            }
        )
        for ((path, text) in sources) {
            dir.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
        }
    }

    fun writeGeneratedCodeProject(
        dir: File,
        kotlin: String,
        buildCache: File? = null,
        shush: String = """
            rule("generated") {
                diagnostics.add("REDUNDANT_VISIBILITY_MODIFIER")
                diagnostics.add("RETURN_VALUE_NOT_USED")
                files.add("build/generated/shush/**")
                files.add("src/generated/kotlin/**")
            }
        """,
    ) = writeProject(
        dir,
        kotlin,
        buildCache = buildCache,
        buildScript = """
            kotlin.sourceSets.main {
                kotlin.srcDir("build/generated/shush/kotlin")
                kotlin.srcDir("src/generated/kotlin")
            }
        """,
        shush = shush,
        sources = GENERATED_SOURCES + HANDWRITTEN_SOURCES,
    )

    fun writeMokkeryProject(kotlin: String, applyShush: Boolean, shush: String? = null) = writeProject(
        projectDir,
        kotlin,
        applyShush = applyShush,
        mokkery = true,
        extraWarnings = false,
        buildScript = """
            dependencies {
                testImplementation(kotlin("test"))
                testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
            }

            tasks.test {
                useJUnitPlatform()
            }
        """,
        shush = shush,
        sources = mapOf("src/test/kotlin/com/example/ServiceTest.kt" to MOKKERY_TEST_SOURCE),
    )

    fun assertMokkeryTestsPassed() {
        val report = projectDir.resolve("build/test-results/test/TEST-com.example.ServiceTest.xml")
        assertTrue(report.isFile, "missing $report")
        val text = report.readText()
        assertContains(text, """tests="2"""")
        assertContains(text, """failures="0"""")
        assertContains(text, """errors="0"""")
        assertFalse(text.contains("<skipped"), text)
    }

    /** Line numbers of unused return value warnings reported for the Mokkery test source. */
    fun unusedValueWarningLines(result: BuildResult): Set<Int> =
        UNUSED_VALUE_WARNING.findAll(result.output).map { it.groupValues[1].toInt() }.toSortedSet()

    fun mokkeryLines(vararg markers: String): Set<Int> =
        MOKKERY_TEST_SOURCE.lines().withIndex()
            .filter { (_, line) -> markers.any { line.endsWith("// $it") } }
            .map { it.index + 1 }
            .toSortedSet()

    /** Lines of all warnings reported for source files, by file name. */
    fun warnings(result: BuildResult): Map<String, Set<Int>> =
        WARNING.findAll(result.output)
            .groupBy({ it.groupValues[1] }, { it.groupValues[2].toInt() })
            .mapValues { (_, lines) -> lines.toSortedSet() }

    /** Lines marked `// WARN` in [sources], by file name (files without warnings are left out). */
    fun expectedWarnings(sources: Map<String, String>): Map<String, Set<Int>> =
        sources.mapNotNull { (path, text) ->
            val lines = text.lines().withIndex().filter { it.value.endsWith("// $WARN") }.map { it.index + 1 }
            if (lines.isEmpty()) null else path.substringAfterLast('/') to lines.toSortedSet()
        }.toMap()
}

private const val CURRENT = "current"
private const val COROUTINES_VERSION = "1.10.2"

/** Genuinely unused values outside of any template. */
private const val GENUINE = "GENUINE"

/** Unused values inside Mokkery templates. */
private const val TEMPLATE = "TEMPLATE"

/** Unused values inside a user-defined template function. */
private const val CUSTOM = "CUSTOM"

/** Marks lines expected to be reported in the custom rule scenarios. */
private const val WARN = "WARN"

private val UNUSED_VALUE_WARNING =
    Regex("""^w: \S*ServiceTest\.kt:(\d+):\d+ .*(?i:unused).*$""", RegexOption.MULTILINE)

private val WARNING = Regex("""^w: \S*?([^/\\]+\.kt):(\d+):\d+ .*$""", RegexOption.MULTILINE)

private val gradleVersions: List<String>
    by lazy { listOf(property("shush.minGradleVersion"), CURRENT) }

private val kotlinVersions: List<String>
    by lazy { property("shush.kotlinVersions").split(';').filter { it.isNotBlank() } }

private val matrix: List<Pair<String, String>> =
    gradleVersions.flatMap { gradle -> kotlinVersions.map { gradle to it } }

private val currentGradleMatrix: List<Pair<String, String>> = kotlinVersions.map { CURRENT to it }

/** The current Gradle version and the build's Kotlin version only. */
private val currentMatrix: List<Pair<String, String>> = listOf(CURRENT to kotlinVersions.first())

private fun property(name: String): String =
    requireNotNull(System.getProperty(name)) { "System property $name is not set" }

private val GENERATED_SOURCES = mapOf(
    "build/generated/shush/kotlin/com/example/generated/Generated.kt" to """
        package com.example.generated

        public class Generated {
            public fun value(): Int = 1

            public fun use() {
                value()
            }
        }
    """.trimIndent() + "\n",
    // Already has a @file:Suppress: shush merges its names into it (@Suppress isn't repeatable), and the
    // generator's own suppression must keep working.
    "build/generated/shush/kotlin/com/example/generated/AlreadySuppressed.kt" to """
        @file:Suppress("UNUSED_VARIABLE")

        package com.example.generated

        public fun alreadySuppressed(): Int {
            val unused = 1
            Generated().value()
            return 2
        }
    """.trimIndent() + "\n",
    "src/generated/kotlin/com/example/generated/Checked.kt" to """
        package com.example.generated

        public object Checked {
            public fun value(): Int = 1

            public fun use() {
                value()
            }
        }
    """.trimIndent() + "\n",
)

/** The same code as the generated sources, in the regular source directory: still reported. */
private val HANDWRITTEN_SOURCES = mapOf(
    "src/main/kotlin/com/example/Handwritten.kt" to """
        package com.example

        public class Handwritten { // WARN
            public fun value(): Int = 1 // WARN

            public fun use() { // WARN
                value() // WARN
            }
        }
    """.trimIndent() + "\n",
)

private val CALL_SOURCES = mapOf(
    "src/main/kotlin/com/example/Stub.kt" to """
        package com.example

        class Empty

        class Stub {
            fun ping(): Empty = Empty()
            fun count(): Int = 1
        }

        fun use(stub: Stub, items: List<Int>) {
            stub.ping()
            stub.count() // WARN
            items.forEach { stub.ping() }
        }
    """.trimIndent() + "\n",
)

private val ANNOTATED_SOURCES = mapOf(
    "src/main/kotlin/com/example/Annotated.kt" to """
        package com.example

        annotation class Legacy

        annotation class Template

        fun compute(): Int = 1

        @Template
        fun template(block: () -> Unit) = block()

        fun plain(block: () -> Unit) = block()

        @Legacy
        public class Old {
            public fun value(): Int = compute()

            fun use() {
                value()
            }
        }

        class Current {
            @Legacy
            public fun legacy() {
                compute()
            }

            public fun current() { // WARN
                compute() // WARN
                template {
                    compute()
                    compute()
                }
                plain {
                    compute() // WARN
                    compute() // WARN
                }
            }
        }
    """.trimIndent() + "\n",
)

private val MOKKERY_TEST_SOURCE = """
    package com.example

    import dev.mokkery.answering.returns
    import dev.mokkery.every
    import dev.mokkery.everySuspend
    import dev.mokkery.matcher.any
    import dev.mokkery.mock
    import dev.mokkery.verify
    import dev.mokkery.verifySuspend
    import kotlinx.coroutines.runBlocking
    import kotlin.test.Test
    import kotlin.test.assertEquals

    interface Repository {
        fun find(id: Int): String
        fun save(value: String): Boolean
        suspend fun load(id: Int): String
    }

    class Service(private val repository: Repository) {
        fun copy(id: Int): Boolean = repository.save(repository.find(id))
        suspend fun reload(id: Int): String = repository.load(id)
    }

    fun myTemplate(block: () -> Unit) = block()

    class ServiceTest {
        private val repository = mock<Repository> {
            every { find(1) } returns "a"
            every { save(any()) } returns true
            everySuspend { load(1) } returns "b"
        }

        @Test
        fun copies() {
            val service = Service(repository)
            service.copy(1) // GENUINE
            verify {
                repository.find(1) // TEMPLATE
                repository.save("a") // TEMPLATE
            }
        }

        @Test
        fun reloads() = runBlocking<Unit> {
            assertEquals("b", Service(repository).reload(1))
            verifySuspend {
                repository.load(1) // TEMPLATE
                repository.load(1) // TEMPLATE
            }
            myTemplate {
                repository.find(1) // CUSTOM
                repository.save("b") // CUSTOM
            }
        }
    }
""".trimIndent() + "\n"
