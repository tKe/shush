package io.github.tke.shush

import io.github.tke.shush.compat.ShushCompatLayers
import io.github.tke.shush.fir.ShushFirExtensionRegistrar
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.MessageCollectorAccess
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.name.FqName
import java.nio.file.Path

class ShushPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String get() = BuildConfig.KOTLIN_PLUGIN_ID

    override val supportsK2: Boolean get() = true

    @OptIn(MessageCollectorAccess::class) // a plain error message: no diagnostic factory fits
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val shushConfiguration = configuration.toShushConfiguration()
        if (shushConfiguration.rules.isEmpty()) return
        // fail early on unsupported compilers, before FIR code links against APIs they lack
        try {
            ShushCompatLayers.current
        } catch (e: IllegalStateException) {
            configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                .report(CompilerMessageSeverity.ERROR, e.message.orEmpty())
            return
        }
        FirExtensionRegistrarAdapter.registerExtension(ShushFirExtensionRegistrar(shushConfiguration))
    }
}

internal fun CompilerConfiguration.toShushConfiguration(): ShushConfiguration {
    val diagnostics = getList(ShushConfigurationKeys.RULE_DIAGNOSTICS)
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.toCollection(mutableSetOf()) }

    fun targets(key: org.jetbrains.kotlin.config.CompilerConfigurationKey<List<Pair<String, String>>>) =
        getList(key).groupBy({ it.first }, { it.second })

    val files = targets(ShushConfigurationKeys.FILES)
    val calls = targets(ShushConfigurationKeys.CALLS)
    val annotatedCalls = targets(ShushConfigurationKeys.ANNOTATED_CALLS)
    val callLambdas = targets(ShushConfigurationKeys.CALL_LAMBDAS)
    val annotatedParameterLambdas = targets(ShushConfigurationKeys.ANNOTATED_PARAMETER_LAMBDAS)
    val annotatedDeclarations = targets(ShushConfigurationKeys.ANNOTATED_DECLARATIONS)

    val targetedRules = files.keys + calls.keys + annotatedCalls.keys + callLambdas.keys + annotatedParameterLambdas.keys +
        annotatedDeclarations.keys
    val undeclared = targetedRules - diagnostics.keys
    if (undeclared.isNotEmpty()) {
        throw CliOptionProcessingException("shush rules have targets but no diagnostics (missing 'rule=<name>=<DIAGNOSTIC>'): $undeclared")
    }

    val custom = diagnostics.map { (name, ruleDiagnostics) ->
        ShushRule(
            name = name,
            diagnostics = ruleDiagnostics,
            files = files[name].orEmpty().toSet(),
            calls = calls[name].orEmpty().mapTo(mutableSetOf(), ::FqName),
            annotatedCalls = annotatedCalls[name].orEmpty().mapTo(mutableSetOf(), ::parseClassId),
            callLambdas = callLambdas[name].orEmpty().mapTo(mutableSetOf(), ::FqName),
            annotatedParameterLambdas = annotatedParameterLambdas[name].orEmpty().mapTo(mutableSetOf(), ::parseClassId),
            annotatedDeclarations = annotatedDeclarations[name].orEmpty().mapTo(mutableSetOf(), ::parseClassId),
        )
    }
    val presets = getList(ShushConfigurationKeys.PRESETS).distinct().map { ShushPresets.all.getValue(it) }

    return ShushConfiguration(
        rules = presets + custom,
        baseDir = get(ShushConfigurationKeys.BASE_DIR)?.let { Path.of(it).toAbsolutePath().normalize() },
    )
}
