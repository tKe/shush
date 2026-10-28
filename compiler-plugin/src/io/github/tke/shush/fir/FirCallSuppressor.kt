package io.github.tke.shush.fir

import io.github.tke.shush.ShushConfiguration
import io.github.tke.shush.fir.Suppressions.suppress
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.SessionAndScopeSessionHolder
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExpressionResolutionExtension
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.calls.ImplicitExtensionReceiverValue
import org.jetbrains.kotlin.fir.resolve.calls.candidate.FirNamedReferenceWithCandidate
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.arrayElementType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations

/**
 * `calls`, `annotatedCalls`, `callLambdas` and `annotatedParameterLambdas` targets.
 *
 * Hooks [FirExpressionResolutionExtension], which runs for each call right after it's resolved during body
 * resolution, before checkers. Never adds implicit receivers.
 *
 * Lambda targets put it on the lambda's body block, which source can't annotate, so there's no clash with a user's
 * `@Suppress` on the lambda (`REPEATED_ANNOTATION`). Call targets annotate the call, as `@Suppress("...") f()` would,
 * merging into one already there. Diagnostics stay pending until the collector reaches the call, so this also catches
 * those reported by declaration checkers, such as `RETURN_VALUE_NOT_USED`.
 */
class FirCallSuppressor(
    session: FirSession,
    private val configuration: ShushConfiguration,
) : FirExpressionResolutionExtension(session) {
    companion object {
        fun getFactory(configuration: ShushConfiguration): Factory =
            Factory { session -> FirCallSuppressor(session, configuration) }
    }

    override fun addNewImplicitReceivers(
        functionCall: FirFunctionCall,
        sessionHolder: SessionAndScopeSessionHolder,
        containingCallableSymbol: FirBasedSymbol<*>,
    ): List<ImplicitExtensionReceiverValue> {
        val callee = functionCall.calleeSymbol() ?: return emptyList()
        functionCall.suppress(callee.callDiagnostics(), anchor = functionCall.source)

        val calleeLambdaDiagnostics = callee.callableId?.asSingleFqName()
            ?.let { configuration.callLambdaDiagnostics[it] }.orEmpty()
        for ((argument, parameter) in functionCall.lambdaArguments()) {
            val diagnostics = calleeLambdaDiagnostics + parameter?.selectedDiagnostics().orEmpty()
            val body = argument.anonymousFunction.body ?: continue
            body.suppress(diagnostics, anchor = argument.anonymousFunction.source)
        }
        return emptyList()
    }

    /** `calls` and `annotatedCalls` diagnostics, suppressed on the whole call. */
    private fun FirCallableSymbol<*>.callDiagnostics(): Set<String> = buildSet {
        callableId?.asSingleFqName()?.let { configuration.callDiagnostics[it]?.let(::addAll) }
        if (configuration.annotatedCallDiagnostics.isNotEmpty()) {
            for (classId in resolvedAnnotationClassIds) configuration.annotatedCallDiagnostics[classId]?.let(::addAll)
        }
    }

    private fun FirValueParameter.selectedDiagnostics(): Set<String> {
        if (configuration.annotatedParameterLambdaDiagnostics.isEmpty()) return emptySet()
        val type = returnTypeRef.coneType
        return buildSet {
            annotations.forEach { addAll(it.selectedDiagnostics()) }
            addAll(type.typeAnnotationDiagnostics())
            // `vararg blocks: @Ann () -> Unit` is typed `Array<out @Ann () -> Unit>`
            if (isVararg) type.arrayElementType()?.let { addAll(it.typeAnnotationDiagnostics()) }
        }
    }

    private fun ConeKotlinType.typeAnnotationDiagnostics(): Set<String> =
        customAnnotations.flatMapTo(mutableSetOf()) { it.selectedDiagnostics() }

    private fun FirAnnotation.selectedDiagnostics(): Set<String> =
        configuration.annotatedParameterLambdaDiagnostics[toAnnotationClassId(session)].orEmpty()

    private fun FirFunctionCall.calleeSymbol(): FirCallableSymbol<*>? =
        calleeReference.toResolvedCallableSymbol()
            ?: (calleeReference as? FirNamedReferenceWithCandidate)?.candidateSymbol as? FirCallableSymbol<*>

    /**
     * Lambda arguments with their parameter. A call whose completion was postponed (e.g. a `verify { }` as the
     * last statement of another lambda) has no resolved mapping yet, so the candidate's is used.
     */
    private fun FirFunctionCall.lambdaArguments(): List<Pair<FirAnonymousFunctionExpression, FirValueParameter?>> {
        val mapping: Map<FirExpression, FirValueParameter>? = resolvedArgumentMapping
            ?: (calleeReference as? FirNamedReferenceWithCandidate)?.candidate
                ?.takeIf { it.argumentMappingInitialized }
                ?.argumentMapping
                ?.mapKeys { (atom, _) -> atom.expression }

        return mapping?.flatMap { (argument, parameter) -> argument.lambdas().map { it to parameter } }
            ?: argumentList.arguments.flatMap { argument -> argument.lambdas().map { it to null } }
    }

    /**
     * Lambdas passed as [this] argument, including each lambda passed to a `vararg` parameter and lambdas
     * SAM-converted to a Java or `fun` interface, which completion wraps in a [FirFunctionTypeConversionExpression].
     */
    private fun FirExpression.lambdas(): List<FirAnonymousFunctionExpression> =
        when (val argument = unwrapArgument()) {
            is FirAnonymousFunctionExpression -> listOf(argument)
            is FirVarargArgumentsExpression -> argument.arguments.flatMap { it.lambdas() }
            is FirFunctionTypeConversionExpression -> argument.expression.lambdas()
            else -> emptyList()
        }
}
