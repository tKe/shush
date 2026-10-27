package io.github.tke.shush.services

import io.github.tke.shush.ShushCommandLineProcessor
import io.github.tke.shush.ShushPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.directives.model.StringDirective
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

fun TestConfigurationBuilder.configurePlugin() {
    useDirectives(ShushDirectives)
    useConfigurators(::ExtensionRegistrarConfigurator)
}

private class ExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    private val commandLineProcessor = ShushCommandLineProcessor()
    private val registrar = ShushPluginRegistrar()

    override val directiveContainers: List<DirectivesContainer>
        get() = listOf(ShushDirectives)

    private val options: List<Pair<StringDirective, AbstractCliOption>> = with(ShushCommandLineProcessor) {
        listOf(
            ShushDirectives.SHUSH_PRESET to PRESET_OPTION,
            ShushDirectives.SHUSH_IGNORABLE_RETURN_TYPES to IGNORABLE_RETURN_TYPES_OPTION,
            ShushDirectives.SHUSH_BASE_DIR to BASE_DIR_OPTION,
            ShushDirectives.SHUSH_RULE to RULE_OPTION,
            ShushDirectives.SHUSH_FILES to FILES_OPTION,
            ShushDirectives.SHUSH_CALLS to CALLS_OPTION,
            ShushDirectives.SHUSH_ANNOTATED_CALLS to ANNOTATED_CALLS_OPTION,
            ShushDirectives.SHUSH_CALL_LAMBDAS to CALL_LAMBDAS_OPTION,
            ShushDirectives.SHUSH_ANNOTATED_PARAMETER_LAMBDAS to ANNOTATED_PARAMETER_LAMBDAS_OPTION,
            ShushDirectives.SHUSH_ANNOTATED_DECLARATIONS to ANNOTATED_DECLARATIONS_OPTION,
        )
    }

    /** Feeds the directives through the real [ShushCommandLineProcessor], as `-P plugin:io.github.tke.shush:...` would. */
    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        for ((directive, option) in options) {
            module.directives[directive]
                .flatMap { it.split(' ', '\t') }
                .filter { it.isNotBlank() }
                .forEach { commandLineProcessor.processOption(option, it.trim(), configuration) }
        }
    }

    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration,
    ) {
        with(registrar) { registerExtensions(configuration) }
    }
}
