package io.github.tke.shush

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * One `rule=<name>=<DIAGNOSTIC>` option per diagnostic (`-P` splits values on commas) plus `<target>=<name>=<value>`
 * options:
 * ```
 * -P plugin:io.github.tke.shush:rule=generated=REDUNDANT_VISIBILITY_MODIFIER
 * -P plugin:io.github.tke.shush:files=generated=build/generated/proto/{star}{star}
 * ```
 * `{star}` is `*`, which would open a nested comment here. Rule options also take `;`-separated lists, see
 * [parseRuleValues].
 */
class ShushCommandLineProcessor : CommandLineProcessor {
    companion object {
        val PRESET_OPTION = option("preset", "<name>", "Enable a built-in rule: ${ShushPresets.all.keys.joinToString()}")
        val RULE_OPTION = option("rule", "<rule>=<DIAGNOSTIC>", "A diagnostic suppressed by a rule (repeat for several)")
        val FILES_OPTION = option("files", "<rule>=<glob>", "Suppress in source files matching a glob")
        val CALLS_OPTION = option("calls", "<rule>=<fq.function>", "Suppress on calls of a function")
        val ANNOTATED_CALLS_OPTION = option(
            "annotatedCalls", "<rule>=<fq.Annotation>",
            "Suppress on calls of functions annotated with an annotation",
        )
        val CALL_LAMBDAS_OPTION = option("callLambdas", "<rule>=<fq.function>", "Suppress in lambdas passed to calls of a function")
        val ANNOTATED_PARAMETER_LAMBDAS_OPTION = option(
            "annotatedParameterLambdas", "<rule>=<fq.Annotation>",
            "Suppress in lambdas passed to parameters annotated (or typed) with an annotation",
        )
        val ANNOTATED_DECLARATIONS_OPTION = option(
            "annotatedDeclarations", "<rule>=<fq.Annotation>",
            "Suppress within declarations annotated with an annotation",
        )
        val IGNORABLE_RETURN_TYPES_OPTION = option(
            "ignorableReturnTypes", "<fq.Class>",
            "Mark functions declared to return this type @IgnorableReturnValue",
        )
        val BASE_DIR_OPTION = CliOption(
            optionName = "baseDir",
            valueDescription = "<path>",
            description = "Directory relative `files` globs are matched against (usually the project directory)",
            required = false,
            allowMultipleOccurrences = false,
        )

        private val ruleTargetOptions = mapOf(
            RULE_OPTION to ShushConfigurationKeys.RULE_DIAGNOSTICS,
            FILES_OPTION to ShushConfigurationKeys.FILES,
            CALLS_OPTION to ShushConfigurationKeys.CALLS,
            ANNOTATED_CALLS_OPTION to ShushConfigurationKeys.ANNOTATED_CALLS,
            CALL_LAMBDAS_OPTION to ShushConfigurationKeys.CALL_LAMBDAS,
            ANNOTATED_PARAMETER_LAMBDAS_OPTION to ShushConfigurationKeys.ANNOTATED_PARAMETER_LAMBDAS,
            ANNOTATED_DECLARATIONS_OPTION to ShushConfigurationKeys.ANNOTATED_DECLARATIONS,
        )

        private fun option(name: String, valueDescription: String, description: String) = CliOption(
            optionName = name,
            valueDescription = valueDescription,
            description = description,
            required = false,
            allowMultipleOccurrences = true,
        )
    }

    override val pluginId: String get() = BuildConfig.KOTLIN_PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> =
        listOf(PRESET_OPTION, IGNORABLE_RETURN_TYPES_OPTION, BASE_DIR_OPTION) + ruleTargetOptions.keys

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option) {
            PRESET_OPTION -> for (preset in value.listItems()) {
                if (preset !in ShushPresets.all) {
                    throw CliOptionProcessingException("Unknown shush preset '$preset', expected one of ${ShushPresets.all.keys}")
                }
                configuration.appendList(ShushConfigurationKeys.PRESETS, preset)
            }
            IGNORABLE_RETURN_TYPES_OPTION -> for (type in value.listItems()) {
                configuration.appendList(ShushConfigurationKeys.IGNORABLE_RETURN_TYPES, type)
            }
            BASE_DIR_OPTION -> configuration.put(ShushConfigurationKeys.BASE_DIR, value)
            else -> {
                val key: CompilerConfigurationKey<List<Pair<String, String>>> = ruleTargetOptions[option]
                    ?: throw CliOptionProcessingException("Unknown option: ${option.optionName}")
                configuration.appendList(key, parseRuleValues(option, value))
            }
        }
    }

    /**
     * `gen=A;B;tmpl=C` is `gen=A`, `gen=B`, `tmpl=C`. Lists are for the Kotlin toolchain, whose
     * `compilerPlugins[].options` map allows each option once. An item containing `=` always starts a new rule.
     */
    private fun parseRuleValues(option: AbstractCliOption, value: String): List<Pair<String, String>> {
        var rule: String? = null
        return value.listItems().map { item ->
            val target = if ('=' in item) {
                rule = item.substringBefore('=').trim()
                item.substringAfter('=').trim()
            } else {
                item
            }
            val currentRule = rule
            if (currentRule.isNullOrBlank() || target.isBlank()) {
                throw CliOptionProcessingException("Expected ${option.optionName}=${option.valueDescription}, got '$value'")
            }
            currentRule to target
        }.ifEmpty {
            throw CliOptionProcessingException("Expected ${option.optionName}=${option.valueDescription}, got '$value'")
        }
    }

    private fun String.listItems(): List<String> = split(';').map(String::trim).filter(String::isNotEmpty)
}
