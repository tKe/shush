package io.github.tke.shush.gradle

import org.gradle.api.Named
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

/**
 * Suppresses [diagnostics] (K2 names such as `RETURN_VALUE_NOT_USED`) wherever any of its targets match. A rule with
 * targets must have diagnostics.
 *
 * Values can't contain commas, which the compiler splits plugin options on. Use two globs rather than `{a,b}`.
 */
abstract class ShushRuleSpec @Inject constructor(private val ruleName: String) : Named {
    companion object {
        /** Placeholder name; the real one is derived when options are rendered. */
        internal const val ANONYMOUS = ""

        /** Reserved for anonymous rules. */
        internal const val ANONYMOUS_PREFIX = "anonymous-"
    }

    override fun getName(): String = ruleName

    internal val isAnonymous: Boolean get() = ruleName == ANONYMOUS

    /** Diagnostics to suppress. */
    abstract val diagnostics: SetProperty<String>

    /** Globs matched against source file paths, relative to the project directory (absolute paths also work). */
    abstract val files: SetProperty<String>

    /** Fully-qualified functions: suppresses on their calls, including the receiver and arguments. */
    abstract val calls: SetProperty<String>

    /** Fully-qualified annotations: suppresses on calls of functions annotated with them. */
    abstract val annotatedCalls: SetProperty<String>

    /** Fully-qualified functions: suppresses within lambdas passed to their calls. */
    abstract val callLambdas: SetProperty<String>

    /** Fully-qualified annotations: suppresses within lambdas passed to parameters annotated (or typed) with them. */
    abstract val annotatedParameterLambdas: SetProperty<String>

    /** Fully-qualified annotations: suppresses within declarations annotated with them. */
    abstract val annotatedDeclarations: SetProperty<String>

    init {
        diagnostics.convention(emptySet())
        files.convention(emptySet())
        calls.convention(emptySet())
        annotatedCalls.convention(emptySet())
        callLambdas.convention(emptySet())
        annotatedParameterLambdas.convention(emptySet())
        annotatedDeclarations.convention(emptySet())
    }
}
