package io.github.tke.shush.fir

import io.github.tke.shush.ShushConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class ShushFirExtensionRegistrar(
    private val configuration: ShushConfiguration,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        if (configuration.hasCallTargets) +FirCallSuppressor.getFactory(configuration)
        if (configuration.hasDeclarationTargets) +FirDeclarationSuppressor.getFactory(configuration)
        if (configuration.ignorableReturnTypes.isNotEmpty()) +FirIgnorableReturnTypes.getFactory(configuration)
    }
}
