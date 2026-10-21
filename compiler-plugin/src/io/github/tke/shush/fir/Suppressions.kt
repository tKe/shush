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
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirCollectionLiteral
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
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
 * `@Suppress` isn't repeatable, so names merge into an existing one:
 * - ours: argument mapping replaced with the union;
 * - user's, unresolved (files and declarations at STATUS): literals appended to its arguments, resolved with the rest;
 * - user's, resolved (e.g. on a lambda): argument mapping extended.
 */
internal object Suppressions {
    private object ShushMarker

    private val sourceKind = KtFakeSourceElementKind.PluginGenerated.Custom(ShushMarker)

    fun FirAnnotationContainer.suppress(
        names: Collection<String>,
        session: FirSession,
        anchor: KtSourceElement?,
        useSiteTarget: AnnotationUseSiteTarget? = null,
    ) {
        if (names.isEmpty()) return
        val source = anchor?.let { ShushCompatLayers.current.fakeElement(it, sourceKind) }

        val own = annotations.firstOrNull { it.source?.kind == sourceKind }
        if (own != null) {
            val missing = names - own.suppressedNames().toSet()
            if (missing.isNotEmpty()) own.replaceArgumentMapping(suppressMapping(own.suppressedNames() + missing, source))
            return
        }

        val existing = annotations.firstOrNull {
            it.useSiteTarget == useSiteTarget && it.isAnnotation(StandardClassIds.Annotations.Suppress, session)
        }
        when (existing) {
            null -> replaceAnnotations(annotations + buildSuppressAnnotation(names, source, useSiteTarget))
            is FirAnnotationCall if existing.argumentMapping.mapping.isEmpty() -> existing.appendArguments(names, source)
            else -> {
                val missing = names - existing.suppressedNames().toSet()
                if (missing.isNotEmpty()) existing.replaceArgumentMapping(suppressMapping(existing.suppressedNames() + missing, source))
            }
        }
    }

    /**
     * Local declarations' annotations aren't resolved yet when their status is, so those are compared by the name as
     * written, qualified or package-relative. Imports and aliases are ignored.
     */
    fun FirAnnotation.isAnnotation(classId: ClassId, session: FirSession): Boolean =
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

    /** `@Suppress("A")` → `@Suppress("A", "B")`; `@Suppress(names = ["A"])` → `@Suppress(names = ["A", "B"])`. */
    private fun FirAnnotationCall.appendArguments(names: Collection<String>, source: KtSourceElement?) {
        val arguments = argumentList.arguments
        val present = arguments.flatMap { it.rawStrings() }.toSet()
        val extra = (names - present).map { literal(it, source, setType = false) }
        if (extra.isEmpty()) return

        val named = arguments.filterIsInstance<FirNamedArgumentExpression>().firstOrNull()
        if (named != null) {
            val array = named.expression as? FirCollectionLiteral ?: return
            array.replaceArgumentList(buildArgumentList {
                this.source = array.argumentList.source
                this.arguments += array.argumentList.arguments
                this.arguments += extra
            })
        } else {
            val old = argumentList
            replaceArgumentList(buildArgumentList {
                this.source = old.source
                this.arguments += old.arguments
                this.arguments += extra
            })
        }
    }

    private fun FirExpression.rawStrings(): List<String> = when (this) {
        is FirLiteralExpression -> listOfNotNull(value as? String)
        is FirNamedArgumentExpression -> expression.rawStrings()
        is FirCollectionLiteral -> argumentList.arguments.flatMap { it.rawStrings() }
        else -> emptyList()
    }

    private fun buildSuppressAnnotation(
        names: Collection<String>,
        source: KtSourceElement?,
        target: AnnotationUseSiteTarget?,
    ): FirAnnotation = buildAnnotation {
        this.source = source
        useSiteTarget = target
        annotationTypeRef = buildResolvedTypeRef {
            coneType = StandardClassIds.Annotations.Suppress.constructClassLikeType()
        }
        argumentMapping = suppressMapping(names, source)
    }

    private fun suppressMapping(names: Collection<String>, source: KtSourceElement?) = buildAnnotationArgumentMapping {
        mapping[StandardClassIds.Annotations.ParameterNames.suppressNames] = buildVarargArgumentsExpression {
            this.source = source
            coneElementTypeOrNull = StandardClassIds.String.constructClassLikeType()
            coneTypeOrNull = StandardClassIds.Array.constructClassLikeType(
                arrayOf(StandardClassIds.String.constructClassLikeType()),
            )
            names.distinct().mapTo(arguments) { literal(it, source, setType = true) }
        }
    }

    private fun literal(name: String, source: KtSourceElement?, setType: Boolean) =
        buildLiteralExpression(source, ConstantValueKind.String, name, setType = setType)
}
