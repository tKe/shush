package io.github.tke.shush.fir

import io.github.tke.shush.ShushConfiguration
import io.github.tke.shush.compat.ShushCompatLayers
import io.github.tke.shush.fir.Suppressions.isAnnotation
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.ReturnValueCheckerMode
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.impl.FirEmptyAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * `ignorableReturnTypes`: annotates Kotlin functions declared to return one of the types `@IgnorableReturnValue`.
 *
 * Runs at STATUS, before the compiler computes the function's return value status from its annotations; setting the
 * status here wouldn't stick, as that computation overwrites it. Explicit return types are resolved by then, inferred
 * ones aren't, so those are skipped. Like `Unit?`, a nullable type doesn't match.
 */
class FirIgnorableReturnTypes(
    session: FirSession,
    private val configuration: ShushConfiguration,
) : FirStatusTransformerExtension(session) {
    companion object {
        fun getFactory(configuration: ShushConfiguration): Factory =
            Factory { session -> FirIgnorableReturnTypes(session, configuration) }
    }

    private object ShushMarker

    private val sourceKind = KtFakeSourceElementKind.PluginGenerated.Custom(ShushMarker)

    private val enabled =
        session.languageVersionSettings.getFlag(AnalysisFlags.returnValueCheckerMode) != ReturnValueCheckerMode.DISABLED

    override fun needTransformStatus(declaration: FirDeclaration): Boolean {
        if (!enabled || declaration !is FirNamedFunction || declaration.origin != FirDeclarationOrigin.Source) return false
        val returnType = (declaration.returnTypeRef as? FirResolvedTypeRef)?.coneType ?: return false
        if (returnType.isMarkedNullable || returnType.classId !in configuration.ignorableReturnTypes) return false
        if (declaration.annotations.any { context(session) { it.isAnnotation(StandardClassIds.Annotations.IgnorableReturnValue) } }) {
            return false
        }

        val source = declaration.source?.let { ShushCompatLayers.current.fakeElement(it, sourceKind) }
        declaration.replaceAnnotations(declaration.annotations + buildAnnotation {
            this.source = source
            annotationTypeRef = buildResolvedTypeRef {
                coneType = StandardClassIds.Annotations.IgnorableReturnValue.constructClassLikeType()
            }
            argumentMapping = FirEmptyAnnotationArgumentMapping
        })
        return false
    }
}
