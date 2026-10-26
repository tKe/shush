package io.github.tke.shush.gradle

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.InternalSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

@Suppress("unused") // Used via reflection.
class ShushGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        val extension = target.extensions.create(EXTENSION_NAME, ShushExtension::class.java)
        extension.mokkery.convention(false)
        target.pluginManager.withPlugin(MOKKERY_PLUGIN_ID) { extension.mokkery.convention(true) }
    }

    // only removes diagnostics, so apply it everywhere
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
        artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
        version = BuildConfig.KOTLIN_PLUGIN_VERSION,
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        val extension = project.extensions.getByType(ShushExtension::class.java)
        val projectPath = project.path
        val projectDir = project.layout.projectDirectory.asFile.absolutePath
        val rules = project.providers.provider {
            (extension.rules + extension.anonymousRules).map(RuleSnapshot::of).distinctBy { it.name }.sortedBy { it.name }
        }
        return extension.mokkery.zip(rules) { mokkery, ruleSnapshots ->
            options(projectPath, projectDir, mokkery, ruleSnapshots)
        }
    }

    private class RuleSnapshot(
        val name: String,
        val isAnonymous: Boolean,
        val diagnostics: Set<String>,
        val targets: List<Pair<String, Set<String>>>,
    ) {
        /** For errors. Anonymous rules' generated names mean nothing to users. */
        fun describe(projectPath: String): String =
            if (isAnonymous) {
                val firstTarget = targets.firstOrNull { it.second.isNotEmpty() }
                    ?.let { (key, values) -> " ($key: ${values.sorted().first()})" }
                    .orEmpty()
                "anonymous shush rule$firstTarget in project '$projectPath'"
            } else {
                "shush rule '$name' in project '$projectPath'"
            }

        companion object {
            fun of(spec: ShushRuleSpec): RuleSnapshot {
                val diagnostics = spec.diagnostics.get()
                val targets = listOf(
                    FILES_OPTION to spec.files.get(),
                    CALLS_OPTION to spec.calls.get(),
                    ANNOTATED_CALLS_OPTION to spec.annotatedCalls.get(),
                    CALL_LAMBDAS_OPTION to spec.callLambdas.get(),
                    ANNOTATED_PARAMETER_LAMBDAS_OPTION to spec.annotatedParameterLambdas.get(),
                    ANNOTATED_DECLARATIONS_OPTION to spec.annotatedDeclarations.get(),
                )
                val name = if (spec.isAnonymous) anonymousName(diagnostics, targets) else spec.name
                return RuleSnapshot(name, spec.isAnonymous, diagnostics, targets)
            }

            /** [ShushRuleSpec.ANONYMOUS_PREFIX] plus a short hash of the rule's (order-independent) contents. */
            private fun anonymousName(diagnostics: Set<String>, targets: List<Pair<String, Set<String>>>): String {
                val canonical = (listOf(RULE_OPTION to diagnostics) + targets)
                    .joinToString("\n") { (key, values) -> "$key=${values.sorted().joinToString("\u0000")}" }
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                return ShushRuleSpec.ANONYMOUS_PREFIX + digest.take(5).joinToString("") { "%02x".format(it) }
            }
        }
    }

    private companion object {
        const val EXTENSION_NAME = "shush"
        const val MOKKERY_PLUGIN_ID = "dev.mokkery"

        const val PRESET_OPTION = "preset"
        const val BASE_DIR_OPTION = "baseDir"
        const val RULE_OPTION = "rule"
        const val FILES_OPTION = "files"
        const val CALLS_OPTION = "calls"
        const val ANNOTATED_CALLS_OPTION = "annotatedCalls"
        const val CALL_LAMBDAS_OPTION = "callLambdas"
        const val ANNOTATED_PARAMETER_LAMBDAS_OPTION = "annotatedParameterLambdas"
        const val ANNOTATED_DECLARATIONS_OPTION = "annotatedDeclarations"

        fun options(projectPath: String, projectDir: String, mokkery: Boolean, rules: List<RuleSnapshot>): List<SubpluginOption> {
            val options = mutableListOf<SubpluginOption>()
            if (mokkery) options += SubpluginOption(PRESET_OPTION, ShushPreset.MOKKERY)
            for (rule in rules) {
                fun fail(problem: String): Nothing =
                    throw InvalidUserDataException("${rule.describe(projectPath)} $problem")

                if (!rule.isAnonymous) {
                    if (rule.name.isBlank() || rule.name.any { it == '=' || it == ',' || it.isWhitespace() }) {
                        fail("has an invalid name: rule names must not be blank or contain '=', ',' or whitespace.")
                    }
                    if (rule.name.startsWith(ShushRuleSpec.ANONYMOUS_PREFIX)) {
                        fail("has an invalid name: the '${ShushRuleSpec.ANONYMOUS_PREFIX}' prefix is reserved for anonymous rules.")
                    }
                }
                val targetCount = rule.targets.sumOf { it.second.size }
                if (targetCount > 0 && rule.diagnostics.isEmpty()) {
                    fail("has targets but no diagnostics: add the diagnostics to suppress, e.g. diagnostics.add(\"RETURN_VALUE_NOT_USED\").")
                }
                if (targetCount == 0) continue
                val values = listOf(RULE_OPTION to rule.diagnostics) + rule.targets
                for ((key, entries) in values) {
                    for (value in entries.sorted()) {
                        if (value.isBlank() || ',' in value) {
                            fail("has an invalid $key value '$value': values must not be blank or contain commas (add several entries instead).")
                        }
                        options += SubpluginOption(key, "${rule.name}=${value.trim()}")
                    }
                }
            }
            if (rules.any { rule -> rule.targets.any { it.first == FILES_OPTION && it.second.isNotEmpty() } }) {
                if (',' in projectDir) {
                    throw InvalidUserDataException(
                        "shush 'files' rules can't be used in project '$projectPath': its directory contains a comma ($projectDir)."
                    )
                }
                // not a task input, so tasks stay relocatable; globs are relative to it, so outputs don't depend on it
                options += InternalSubpluginOption(BASE_DIR_OPTION, projectDir)
            }
            return options
        }
    }
}

private object ShushPreset {
    const val MOKKERY = "mokkery"
}
