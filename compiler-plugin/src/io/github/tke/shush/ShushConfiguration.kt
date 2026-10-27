package io.github.tke.shush

import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher

/**
 * A named set of [diagnostics] to suppress wherever any of its targets match:
 * - [files]: source files whose path matches a glob (relative to [ShushConfiguration.baseDir] where possible);
 * - [calls]: calls of these functions, including their receivers and arguments;
 * - [annotatedCalls]: calls of functions annotated with one of these, including their receivers and arguments;
 * - [callLambdas]: lambdas passed to calls of these functions;
 * - [annotatedParameterLambdas]: lambdas passed to a parameter annotated with (or whose type is annotated with) one of
 *   these;
 * - [annotatedDeclarations]: declarations (and everything within them) annotated with one of these.
 */
data class ShushRule(
    val name: String,
    val diagnostics: Set<String> = emptySet(),
    val files: Set<String> = emptySet(),
    val calls: Set<FqName> = emptySet(),
    val annotatedCalls: Set<ClassId> = emptySet(),
    val callLambdas: Set<FqName> = emptySet(),
    val annotatedParameterLambdas: Set<ClassId> = emptySet(),
    val annotatedDeclarations: Set<ClassId> = emptySet(),
) {
    val hasCallTargets: Boolean
        get() = calls.isNotEmpty() || annotatedCalls.isNotEmpty() || callLambdas.isNotEmpty() ||
            annotatedParameterLambdas.isNotEmpty()

    val hasDeclarationTargets: Boolean
        get() = files.isNotEmpty() || annotatedDeclarations.isNotEmpty()
}

/**
 * [ignorableReturnTypes]: Kotlin functions declared to return one of these (non-null) types are marked
 * `@IgnorableReturnValue`, as if written in source. Unlike rules, this changes what's compiled: the status goes into
 * metadata, so callers in other modules don't need shush.
 */
data class ShushConfiguration(
    val rules: List<ShushRule>,
    val baseDir: Path? = null,
    val ignorableReturnTypes: Set<ClassId> = emptySet(),
) {
    /** Lookup tables from each kind of target to the diagnostics its rules suppress. */
    val callDiagnostics: Map<FqName, Set<String>> = index { it.calls }
    val annotatedCallDiagnostics: Map<ClassId, Set<String>> = index { it.annotatedCalls }
    val callLambdaDiagnostics: Map<FqName, Set<String>> = index { it.callLambdas }
    val annotatedParameterLambdaDiagnostics: Map<ClassId, Set<String>> = index { it.annotatedParameterLambdas }
    val annotatedDeclarationDiagnostics: Map<ClassId, Set<String>> = index { it.annotatedDeclarations }
    val fileMatchers: List<Pair<PathMatcher, Set<String>>> = rules.flatMap { rule ->
        rule.files.map { glob -> FileSystems.getDefault().getPathMatcher("glob:$glob") to rule.diagnostics }
    }

    val hasCallTargets: Boolean get() = rules.any { it.hasCallTargets }
    val hasDeclarationTargets: Boolean get() = rules.any { it.hasDeclarationTargets }
    val isEmpty: Boolean get() = rules.isEmpty() && ignorableReturnTypes.isEmpty()

    /** Diagnostics to suppress in the source file at [path], tried both relative to [baseDir] and as-is. */
    fun fileDiagnostics(path: Path): Set<String> {
        if (fileMatchers.isEmpty()) return emptySet()
        val candidates = listOfNotNull(
            baseDir?.takeIf { path.startsWith(it) }?.relativize(path),
            path,
        )
        return fileMatchers
            .filter { (matcher, _) -> candidates.any(matcher::matches) }
            .flatMapTo(mutableSetOf()) { (_, diagnostics) -> diagnostics }
    }

    private fun <K> index(targets: (ShushRule) -> Set<K>): Map<K, Set<String>> =
        buildMap<K, MutableSet<String>> {
            for (rule in rules) {
                for (target in targets(rule)) getOrPut(target, ::mutableSetOf) += rule.diagnostics
            }
        }
}

object ShushPresets {
    const val MOKKERY = "mokkery"

    /** Mokkery's `every`/`verify` lambdas are call templates: their expressions' values are never used. */
    val mokkery = ShushRule(
        name = MOKKERY,
        diagnostics = setOf("RETURN_VALUE_NOT_USED", "RETURN_VALUE_NOT_USED_COERCION"),
        callLambdas = setOf(
            FqName("dev.mokkery.every"),
            FqName("dev.mokkery.everySuspend"),
            FqName("dev.mokkery.verify"),
            FqName("dev.mokkery.verifySuspend"),
        ),
        annotatedParameterLambdas = setOf(ClassId.fromString("dev/mokkery/internal/annotations/Templating")),
    )

    val all: Map<String, ShushRule> = listOf(mokkery).associateBy { it.name }
}

/**
 * Parses a class name given either as a [ClassId] string (`com/example/Outer.Inner`) or, for top-level classes, as a
 * plain fully-qualified name (`com.example.Template`).
 */
fun parseClassId(value: String): ClassId =
    if ('/' in value) ClassId.fromString(value) else ClassId.topLevel(FqName(value))

object ShushConfigurationKeys {
    val PRESETS: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey.create("shush presets")
    val RULE_DIAGNOSTICS: CompilerConfigurationKey<List<Pair<String, String>>> =
        CompilerConfigurationKey.create("shush rule diagnostics")
    val FILES: CompilerConfigurationKey<List<Pair<String, String>>> = CompilerConfigurationKey.create("shush file globs")
    val CALLS: CompilerConfigurationKey<List<Pair<String, String>>> = CompilerConfigurationKey.create("shush calls")
    val ANNOTATED_CALLS: CompilerConfigurationKey<List<Pair<String, String>>> =
        CompilerConfigurationKey.create("shush annotated calls")
    val CALL_LAMBDAS: CompilerConfigurationKey<List<Pair<String, String>>> = CompilerConfigurationKey.create("shush call lambdas")
    val ANNOTATED_PARAMETER_LAMBDAS: CompilerConfigurationKey<List<Pair<String, String>>> =
        CompilerConfigurationKey.create("shush annotated parameter lambdas")
    val ANNOTATED_DECLARATIONS: CompilerConfigurationKey<List<Pair<String, String>>> =
        CompilerConfigurationKey.create("shush annotated declarations")
    val IGNORABLE_RETURN_TYPES: CompilerConfigurationKey<List<String>> =
        CompilerConfigurationKey.create("shush ignorable return types")
    val BASE_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("shush base directory")
}
