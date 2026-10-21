package io.github.tke.shush.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs real builds of Kotlin JVM projects, resolving this Gradle plugin and the compiler plugin from the build-local
 * repository the `test` task publishes to. Scenarios run against the oldest supported and the current Gradle version
 * and every Kotlin version in `shush.kotlinVersions`: the published compiler plugin is a single jar for all supported
 * compilers.
 */
class ShushGradlePluginFunctionalTest {
    @TempDir
    lateinit var tempDir: File

    private val projectDir: File get() = tempDir.resolve("project")

    // ---------------------------------------------------------------------------------------------------------------
    // Mokkery preset

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("mokkeryMatrix")
    fun `mokkery preset is enabled by convention, keeps genuine warnings, tests pass and configuration cache is reused`(
        gradle: String,
        kotlin: String,
    ) {
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

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("mokkeryMatrix")
    fun `without the plugin Mokkery template warnings are reported`(gradle: String, kotlin: String) {
        writeMokkeryProject(kotlin, applyShush = false)

        val result = runner(gradle, projectDir, "compileTestKotlin").build()
        assertEquals(mokkeryLines(GENUINE, CUSTOM, TEMPLATE), unusedValueWarningLines(result), result.output)
    }

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("mokkeryMatrix")
    fun `mokkery set to false disables the preset`(gradle: String, kotlin: String) {
        writeMokkeryProject(kotlin, applyShush = true, shush = "mokkery.set(false)")

        val result = runner(gradle, projectDir, "compileTestKotlin", "--configuration-cache").build()
        assertEquals(mokkeryLines(GENUINE, CUSTOM, TEMPLATE), unusedValueWarningLines(result), result.output)
    }

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("mokkeryMatrix")
    fun `custom call rules combine with the mokkery preset`(gradle: String, kotlin: String) {
        writeMokkeryProject(
            kotlin,
            applyShush = true,
            shush = """
                mokkery()
                rule("my-templates") {
                    diagnostics.add("RETURN_VALUE_NOT_USED")
                    diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
                    calls.add("com.example.myTemplate")
                }
            """,
        )

        val result = runner(gradle, projectDir, "test", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertEquals(mokkeryLines(GENUINE), unusedValueWarningLines(result), result.output)
        assertMokkeryTestsPassed()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Custom rules

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("matrix")
    fun `files rule suppresses warnings in generated sources only`(gradle: String, kotlin: String) {
        writeGeneratedCodeProject(projectDir, kotlin)

        val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
        assertEquals(expectedWarnings(GENERATED_SOURCES + HANDWRITTEN_SOURCES), warnings(result), result.output)
    }

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("matrix")
    fun `without rules generated sources are reported`(gradle: String, kotlin: String) {
        writeGeneratedCodeProject(projectDir, kotlin, shush = "")

        val result = runner(gradle, projectDir, "compileKotlin").build()
        val all = warnings(result)
        for (file in GENERATED_SOURCES.keys.map { it.substringAfterLast('/') }) {
            assertTrue(all[file].orEmpty().isNotEmpty(), "expected warnings in $file:\n${result.output}")
        }
        assertEquals(expectedWarnings(HANDWRITTEN_SOURCES).getValue("Handwritten.kt"), all["Handwritten.kt"], result.output)
        // Its own @file:Suppress("UNUSED_VARIABLE") hides line 6.
        assertEquals(setOf(5, 7), all["AlreadySuppressed.kt"], result.output)
    }

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("matrix")
    fun `annotatedDeclarations and annotatedFunctions rules suppress within annotated code`(gradle: String, kotlin: String) {
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
                    annotatedFunctions.add("com.example.Template")
                }
            """,
            sources = ANNOTATED_SOURCES,
        )

        val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
        assertEquals(expectedWarnings(ANNOTATED_SOURCES), warnings(result), result.output)
    }

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("currentGradleMatrix")
    fun `a rule with targets but no diagnostics fails the build`(gradle: String, kotlin: String) {
        writeGeneratedCodeProject(projectDir, kotlin, shush = """rule("broken") { files.add("build/generated/**") }""")

        val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").buildAndFail()
        assertContains(result.output, "shush rule 'broken' in project ':' has targets but no diagnostics")
    }

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("currentGradleMatrix")
    fun `compilation is relocatable through the build cache`(gradle: String, kotlin: String) {
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

    // ---------------------------------------------------------------------------------------------------------------
    // Anonymous rules (Gradle plugin logic only: the compiler sees ordinary named rules, so one Kotlin version suffices)

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("currentMatrix")
    fun `anonymous rules combine with named rules and the mokkery preset`(gradle: String, kotlin: String) {
        writeMokkeryProject(
            kotlin,
            applyShush = true,
            shush = """
                rule {
                    diagnostics.add("RETURN_VALUE_NOT_USED")
                    diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
                    calls.add("com.example.myTemplate")
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

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("currentMatrix")
    fun `distinct anonymous rules all apply and their declaration order does not affect the compile task`(
        gradle: String,
        kotlin: String,
    ) {
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
                annotatedFunctions.add("com.example.Template")
            }
            """.trimIndent(),
            """
            rule {
                annotatedFunctions.add("com.example.Template")
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

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("currentMatrix")
    fun `an anonymous rule with targets but no diagnostics fails the build without its generated name`(
        gradle: String,
        kotlin: String,
    ) {
        writeGeneratedCodeProject(projectDir, kotlin, shush = """rule { files.add("build/generated/**") }""")

        val result = runner(gradle, projectDir, "compileKotlin", "--configuration-cache").buildAndFail()
        assertContains(
            result.output,
            "anonymous shush rule (files: build/generated/**) in project ':' has targets but no diagnostics",
        )
        assertFalse(result.output.contains("anonymous-"), result.output)
    }

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @MethodSource("currentMatrix")
    fun `named rules can't use the anonymous rule prefix`(gradle: String, kotlin: String) {
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

    // ---------------------------------------------------------------------------------------------------------------
    // Project setup

    private fun runner(gradle: String, dir: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()
            .apply {
                if (gradle == CURRENT) withGradleInstallation(File(property("shush.currentGradleHome")))
                else withGradleVersion(gradle)
            }

    private fun writeProject(
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

    private fun writeGeneratedCodeProject(
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

    private fun writeMokkeryProject(kotlin: String, applyShush: Boolean, shush: String? = null) = writeProject(
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

    private fun assertMokkeryTestsPassed() {
        val report = projectDir.resolve("build/test-results/test/TEST-com.example.ServiceTest.xml")
        assertTrue(report.isFile, "missing $report")
        val text = report.readText()
        assertContains(text, """tests="2"""")
        assertContains(text, """failures="0"""")
        assertContains(text, """errors="0"""")
        assertFalse(text.contains("<skipped"), text)
    }

    /** Line numbers of unused return value warnings reported for the Mokkery test source. */
    private fun unusedValueWarningLines(result: BuildResult): Set<Int> =
        UNUSED_VALUE_WARNING.findAll(result.output).map { it.groupValues[1].toInt() }.toSortedSet()

    private fun mokkeryLines(vararg markers: String): Set<Int> =
        MOKKERY_TEST_SOURCE.lines().withIndex()
            .filter { (_, line) -> markers.any { line.endsWith("// $it") } }
            .map { it.index + 1 }
            .toSortedSet()

    /** Lines of all warnings reported for source files, by file name. */
    private fun warnings(result: BuildResult): Map<String, Set<Int>> =
        WARNING.findAll(result.output)
            .groupBy({ it.groupValues[1] }, { it.groupValues[2].toInt() })
            .mapValues { (_, lines) -> lines.toSortedSet() }

    /** Lines marked `// WARN` in [sources], by file name (files without warnings are left out). */
    private fun expectedWarnings(sources: Map<String, String>): Map<String, Set<Int>> =
        sources.mapNotNull { (path, text) ->
            val lines = text.lines().withIndex().filter { it.value.endsWith("// $WARN") }.map { it.index + 1 }
            if (lines.isEmpty()) null else path.substringAfterLast('/') to lines.toSortedSet()
        }.toMap()

    companion object {
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

        private fun gradleVersions(): List<String> = listOf(property("shush.minGradleVersion"), CURRENT)

        private fun kotlinVersions(): List<String> = property("shush.kotlinVersions").split(';').filter { it.isNotBlank() }

        @JvmStatic
        fun matrix(): List<Arguments> =
            gradleVersions().flatMap { gradle -> kotlinVersions().map { Arguments.of(gradle, it) } }

        @JvmStatic
        fun currentGradleMatrix(): List<Arguments> = kotlinVersions().map { Arguments.of(CURRENT, it) }

        @JvmStatic
        fun mokkeryMatrix(): List<Arguments> = matrix()

        /** The current Gradle version and the build's Kotlin version only. */
        @JvmStatic
        fun currentMatrix(): List<Arguments> = listOf(Arguments.of(CURRENT, kotlinVersions().first()))

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
    }
}
