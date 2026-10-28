package io.github.tke.shush.fir

import io.github.tke.shush.compat.ShushCompatLayers
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.unwrapVarargValue
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildVarargArgumentsExpression
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Adds names to a container's `@Suppress`. FIR has no diagnostic suppressor extension point, but the pending
 * diagnostics reporter drops anything under an enclosing `@Suppress`, so one has to be there before checkers run.
 *
 * Ours is a separate, already resolved, plain [FirAnnotation]: suppression reads every `@Suppress` on a container, and
 * the IDE rebuilds unresolved annotation arguments from their PSI, which would drop names added to the user's. Its
 * container must be past TYPES, which rejects plain annotations; declarations are at STATUS, files are resolved first.
 *
 * `@Suppress` isn't repeatable, but the repeat check compares types and suppression only class IDs, so ours is typed
 * `Suppress?`, which can't be written. Any `Suppress?` is merged into, never repeated, so it's found by type, not source.
 */
internal object Suppressions {
    private object ShushMarker

    private val sourceKind = KtFakeSourceElementKind.PluginGenerated.Custom(ShushMarker)

    private val suppressType = StandardClassIds.Annotations.Suppress.constructClassLikeType(isMarkedNullable = true)

    fun FirAnnotationContainer.suppress(
        names: Collection<String>,
        anchor: KtSourceElement?,
        useSiteTarget: AnnotationUseSiteTarget? = null,
    ) {
        if (names.isEmpty()) return
        val source = anchor?.let { ShushCompatLayers.current.fakeElement(it, sourceKind) }

        val own = annotations.firstOrNull { (it.annotationTypeRef as? FirResolvedTypeRef)?.coneType == suppressType }
        if (own == null) {
            replaceAnnotations(annotations + buildAnnotation {
                this.source = source
                this.useSiteTarget = useSiteTarget
                annotationTypeRef = buildResolvedTypeRef { coneType = suppressType }
                argumentMapping = suppressMapping(names, source)
            })
        } else {
            val missing = names - own.suppressedNames().toSet()
            if (missing.isNotEmpty()) own.replaceArgumentMapping(suppressMapping(own.suppressedNames() + missing, source))
        }
    }

    /**
     * Local declarations' annotations aren't resolved yet when their status is, so those are compared by the name as
     * written, qualified or package-relative. Imports and aliases are ignored.
     */
    context(session: FirSession)
    fun FirAnnotation.isAnnotation(classId: ClassId): Boolean =
        when (val typeRef = annotationTypeRef) {
            is FirResolvedTypeRef -> toAnnotationClassId(session) == classId
            is FirUserTypeRef -> typeRef.qualifier.joinToString(".") { it.name.asString() }.let { written ->
                written == classId.relativeClassName.asString() || written == classId.asSingleFqName().asString()
            }
            else -> false
        }

    private fun FirAnnotation.suppressedNames(): List<String> =
        argumentMapping.mapping[StandardClassIds.Annotations.ParameterNames.suppressNames]
            ?.unwrapVarargValue()
            ?.mapNotNull { (it as? FirLiteralExpression)?.value as? String }
            .orEmpty()

    private fun suppressMapping(names: Collection<String>, source: KtSourceElement?) = buildAnnotationArgumentMapping {
        mapping[StandardClassIds.Annotations.ParameterNames.suppressNames] = buildVarargArgumentsExpression {
            this.source = source
            coneElementTypeOrNull = StandardClassIds.String.constructClassLikeType()
            coneTypeOrNull = StandardClassIds.Array.constructClassLikeType(
                arrayOf(StandardClassIds.String.constructClassLikeType()),
            )
            names.distinct().mapTo(arguments) { buildLiteralExpression(source, ConstantValueKind.String, it, setType = true) }
        }
    }
}
