package io.github.tke.shush.services

import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer

/**
 * Test directives mirroring the plugin's command line options (see `ShushCommandLineProcessor`), e.g.
 * ```
 * // SHUSH_PRESET: mokkery
 * // SHUSH_RULE: generated=UNUSED_VARIABLE generated=DEPRECATION
 * // SHUSH_FILES: generated=generated/Gen.kt
 * ```
 * Directives can be repeated and hold several whitespace-separated values (not commas, which globs use). Each value is
 * passed to the processor like one `-P plugin:io.github.tke.shush:<option>=<value>`.
 */
object ShushDirectives : SimpleDirectivesContainer() {
    val SHUSH_PRESET by stringDirective("Value(s) of the `preset` option (<name>)")
    val SHUSH_RULE by stringDirective("Value(s) of the `rule` option (<rule>=<DIAGNOSTIC>)")
    val SHUSH_FILES by stringDirective("Value(s) of the `files` option (<rule>=<glob>)")
    val SHUSH_CALLS by stringDirective("Value(s) of the `calls` option (<rule>=<fq.function>)")
    val SHUSH_ANNOTATED_CALLS by stringDirective("Value(s) of the `annotatedCalls` option (<rule>=<fq.Annotation>)")
    val SHUSH_CALL_LAMBDAS by stringDirective("Value(s) of the `callLambdas` option (<rule>=<fq.function>)")
    val SHUSH_ANNOTATED_PARAMETER_LAMBDAS by stringDirective("Value(s) of the `annotatedParameterLambdas` option (<rule>=<fq.Annotation>)")
    val SHUSH_ANNOTATED_DECLARATIONS by stringDirective("Value(s) of the `annotatedDeclarations` option (<rule>=<fq.Annotation>)")
    val SHUSH_IGNORABLE_RETURN_TYPES by stringDirective("Value(s) of the `ignorableReturnTypes` option (<fq.Class>)")
    val SHUSH_BASE_DIR by stringDirective("Value of the `baseDir` option (<path>)")
}
