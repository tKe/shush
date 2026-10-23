package io.github.tke.shush

import de.infix.testBalloon.framework.core.testSuite
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

@OptIn(CompilerConfiguration.Internals::class)
val ShushConfigurationTest by testSuite {
    val processor = ShushCommandLineProcessor()

    fun configuration(vararg options: Pair<AbstractCliOption, String>): CompilerConfiguration =
        CompilerConfiguration().apply {
            for ((option, value) in options) processor.processOption(option, value, this)
        }

    test("rules collect their diagnostics and targets") {
        val configuration = configuration(
            ShushCommandLineProcessor.RULE_OPTION to "gen=UNUSED_VARIABLE",
            ShushCommandLineProcessor.RULE_OPTION to "gen=DEPRECATION",
            ShushCommandLineProcessor.FILES_OPTION to "gen=build/{a,b}/x=y/**",
            ShushCommandLineProcessor.CALLS_OPTION to "gen=com.example.template",
            ShushCommandLineProcessor.ANNOTATED_PARAMETERS_OPTION to "gen=com.example.Param",
            ShushCommandLineProcessor.ANNOTATED_FUNCTIONS_OPTION to "gen=com/example/Outer.Function",
            ShushCommandLineProcessor.ANNOTATED_DECLARATIONS_OPTION to "gen=com.example.Generated",
            ShushCommandLineProcessor.BASE_DIR_OPTION to "/project",
        ).toShushConfiguration()

        assertEquals(
            listOf(
                ShushRule(
                    name = "gen",
                    diagnostics = setOf("UNUSED_VARIABLE", "DEPRECATION"),
                    files = setOf("build/{a,b}/x=y/**"),
                    calls = setOf(FqName("com.example.template")),
                    annotatedParameters = setOf(ClassId.fromString("com/example/Param")),
                    annotatedFunctions = setOf(ClassId.fromString("com/example/Outer.Function")),
                    annotatedDeclarations = setOf(ClassId.fromString("com/example/Generated")),
                ),
            ),
            configuration.rules,
        )
        assertEquals(Path.of("/project"), configuration.baseDir)
        assertEquals(setOf("UNUSED_VARIABLE", "DEPRECATION"), configuration.fileDiagnostics(Path.of("/project/build/a/x=y/Gen.kt")))
        assertEquals(emptySet(), configuration.fileDiagnostics(Path.of("/project/build/c/x=y/Gen.kt")))
    }

    test("file globs match relative to the base directory and as absolute paths") {
        val configuration = configuration(
            ShushCommandLineProcessor.RULE_OPTION to "relative=UNUSED_VARIABLE",
            ShushCommandLineProcessor.FILES_OPTION to "relative=src/generated/**",
            ShushCommandLineProcessor.RULE_OPTION to "absolute=DEPRECATION",
            ShushCommandLineProcessor.FILES_OPTION to "absolute=**/generated/**",
            ShushCommandLineProcessor.BASE_DIR_OPTION to "/project",
        ).toShushConfiguration()

        assertEquals(setOf("UNUSED_VARIABLE", "DEPRECATION"), configuration.fileDiagnostics(Path.of("/project/src/generated/a/Gen.kt")))
        assertEquals(setOf("DEPRECATION"), configuration.fileDiagnostics(Path.of("/elsewhere/src/generated/Gen.kt")))
        assertEquals(emptySet(), configuration.fileDiagnostics(Path.of("/project/src/main/Main.kt")))
    }

    test("options may hold semicolon-separated lists, items without a rule continuing the previous one") {
        // the Kotlin toolchain's `compilerPlugins[].options` map allows each option once
        val configuration = configuration(
            ShushCommandLineProcessor.PRESET_OPTION to "mokkery; mokkery",
            ShushCommandLineProcessor.RULE_OPTION to "gen=UNUSED_VARIABLE;DEPRECATION; tmpl=RETURN_VALUE_NOT_USED;",
            ShushCommandLineProcessor.FILES_OPTION to "gen=src/gen/**;build/gen/**",
            ShushCommandLineProcessor.ANNOTATED_DECLARATIONS_OPTION to "tmpl=com.example.Template",
        ).toShushConfiguration()

        assertEquals(
            listOf(
                ShushPresets.mokkery,
                ShushRule(
                    name = "gen",
                    diagnostics = setOf("UNUSED_VARIABLE", "DEPRECATION"),
                    files = setOf("src/gen/**", "build/gen/**"),
                ),
                ShushRule(
                    name = "tmpl",
                    diagnostics = setOf("RETURN_VALUE_NOT_USED"),
                    annotatedDeclarations = setOf(ClassId.fromString("com/example/Template")),
                ),
            ),
            configuration.rules,
        )
    }

    test("a list item containing = always starts with a rule name") {
        val error = assertFailsWith<CliOptionProcessingException> {
            configuration(
                ShushCommandLineProcessor.RULE_OPTION to "gen=UNUSED_VARIABLE",
                ShushCommandLineProcessor.FILES_OPTION to "gen=src/gen/**;build/x=y/**",
            ).toShushConfiguration()
        }
        assertContains(error.message.orEmpty(), "build/x")
    }

    test("presets are built in rules") {
        val configuration = configuration(
            ShushCommandLineProcessor.PRESET_OPTION to "mokkery",
            ShushCommandLineProcessor.PRESET_OPTION to "mokkery",
        ).toShushConfiguration()

        assertEquals(listOf(ShushPresets.mokkery), configuration.rules)
        assertTrue(configuration.hasLambdaTargets)
        assertEquals(
            setOf("RETURN_VALUE_NOT_USED", "RETURN_VALUE_NOT_USED_COERCION"),
            configuration.callDiagnostics[FqName("dev.mokkery.verify")],
        )
    }

    test("no options means no rules") {
        assertEquals(emptyList(), CompilerConfiguration().toShushConfiguration().rules)
    }

    test("targets of an undeclared rule are rejected") {
        val configuration = configuration(
            ShushCommandLineProcessor.RULE_OPTION to "declared=UNUSED_VARIABLE",
            ShushCommandLineProcessor.CALLS_OPTION to "declared=com.example.f",
            ShushCommandLineProcessor.FILES_OPTION to "typo=**/generated/**",
        )
        val error = assertFailsWith<CliOptionProcessingException> { configuration.toShushConfiguration() }
        assertContains(error.message.orEmpty(), "typo")
        assertContains(error.message.orEmpty(), "rule=<name>=<DIAGNOSTIC>")
    }

    test("unknown presets are rejected") {
        val error = assertFailsWith<CliOptionProcessingException> {
            configuration(ShushCommandLineProcessor.PRESET_OPTION to "nope")
        }
        assertContains(error.message.orEmpty(), "nope")
        assertContains(error.message.orEmpty(), "mokkery")
    }

    test("malformed rule options are rejected") {
        for (value in listOf("UNUSED_VARIABLE", "=UNUSED_VARIABLE", "rule=", " =x")) {
            assertFailsWith<CliOptionProcessingException>(value) {
                configuration(ShushCommandLineProcessor.RULE_OPTION to value)
            }
        }
    }
}
