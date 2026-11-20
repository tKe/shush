package io.github.tke.shush.fir

import io.github.tke.shush.ShushConfiguration
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.ReturnValueCheckerMode
import org.jetbrains.kotlin.contracts.description.KtReturnsResultOfDeclaration
import org.jetbrains.kotlin.contracts.description.KtValueParameterReference
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.SessionAndScopeSessionHolder
import org.jetbrains.kotlin.fir.contracts.FirResolvedContractDescription
import org.jetbrains.kotlin.fir.contracts.builder.buildEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.builder.buildResolvedContractDescription
import org.jetbrains.kotlin.fir.declarations.FirContractDescriptionOwner
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.extensions.FirExpressionResolutionExtension
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.calls.ImplicitExtensionReceiverValue
import org.jetbrains.kotlin.fir.resolve.calls.candidate.FirNamedReferenceWithCandidate
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import java.util.concurrent.ConcurrentHashMap

/**
 * `returnsResultOf`: gives configured library functions a `returnsResultOf` contract for some of their lambda
 * parameters, as if they declared it, so the return value checker looks through calls of them to the lambdas' results.
 *
 * Only the deserialized declaration in this compilation's memory changes: nothing is written to the output, so unlike a
 * real contract in the library, there's no pre-release binary. Of all the compiler, only the return value checker reads
 * the effect (data flow and inference read other effects only), so adding it partway through body resolution can't
 * change resolution.
 *
 * Hooks [FirExpressionResolutionExtension], which runs for each call once it's resolved, before checkers. Never adds
 * implicit receivers. Each matching callee is changed on its first call, as is the declaration a substitution override
 * comes from, so later overrides see the effect too.
 */
class FirReturnsResultOfContracts(
    session: FirSession,
    private val configuration: ShushConfiguration,
) : FirExpressionResolutionExtension(session) {
    companion object {
        fun getFactory(configuration: ShushConfiguration): Factory =
            Factory { session -> FirReturnsResultOfContracts(session, configuration) }
    }

    private val enabled =
        session.languageVersionSettings.getFlag(AnalysisFlags.returnValueCheckerMode) != ReturnValueCheckerMode.DISABLED

    /** Callees already given their contract, or found not to need one. */
    private val processed: MutableSet<FirFunctionSymbol<*>> = ConcurrentHashMap.newKeySet()

    override fun addNewImplicitReceivers(
        functionCall: FirFunctionCall,
        sessionHolder: SessionAndScopeSessionHolder,
        containingCallableSymbol: FirBasedSymbol<*>,
    ): List<ImplicitExtensionReceiverValue> {
        if (!enabled) return emptyList()
        val callee = functionCall.calleeSymbol() ?: return emptyList()
        if (callee in processed) return emptyList()
        val lambdas = callee.callableId?.asSingleFqName()?.let(configuration.returnsResultOf::get)
        if (lambdas != null) {
            val original = callee.unwrapFakeOverrides()
            if (original.origin.isLibrary) {
                val parameters = original.propagatingParameters(lambdas)
                if (parameters.isNotEmpty()) {
                    original.addReturnsResultOf(parameters)
                    if (callee != original) callee.addReturnsResultOf(parameters)
                }
            }
        }
        processed += callee
        return emptyList()
    }

    private val FirDeclarationOrigin.isLibrary: Boolean
        get() = this == FirDeclarationOrigin.Library || isBuiltIns

    /**
     * Value parameter indices (as contracts count them: receivers excluded) and names of the lambdas whose results
     * the call returns: those [named], or, if none are, those whose function type returns the function's return type.
     * The latter leaves out builder lambdas, like `buildList`'s `MutableList<E>.() -> Unit`.
     */
    private fun FirFunctionSymbol<*>.propagatingParameters(named: Set<String>): Map<Int, String> {
        val returnType = resolvedReturnType
        return valueParameterSymbols.withIndex()
            .filter { (_, parameter) ->
                if (named.isNotEmpty()) {
                    parameter.name.asString() in named
                } else {
                    val type = parameter.resolvedReturnType
                    !parameter.isVararg && type.isSomeFunctionType(session) && type.typeArguments.lastOrNull()?.type == returnType
                }
            }
            .associate { (index, parameter) -> index to parameter.name.asString() }
    }

    /**
     * Adds an effect for each of [parameters] the declaration doesn't already have. Changing a library declaration
     * means using its FIR, which symbols otherwise hide.
     */
    @OptIn(SymbolInternals::class)
    private fun FirFunctionSymbol<*>.addReturnsResultOf(parameters: Map<Int, String>) {
        val owner = fir as? FirContractDescriptionOwner ?: return
        synchronized(owner) {
            val current = resolvedContractDescription // resolves a lazy contract first
            // anything else is unresolved, or an error; leave it be
            if (owner.contractDescription.let { it != null && it !is FirResolvedContractDescription }) return
            val existing = current?.effects.orEmpty().mapNotNullTo(mutableSetOf()) {
                (it.effect as? KtReturnsResultOfDeclaration)?.valueParameterReference?.parameterIndex
            }
            val missing = parameters - existing
            if (missing.isEmpty()) return
            owner.replaceContractDescription(current.withReturnsResultOf(missing))
        }
    }

    /** This contract (or an empty one) plus a `returnsResultOf` effect for each of [parameters]. */
    private fun FirResolvedContractDescription?.withReturnsResultOf(parameters: Map<Int, String>) =
        buildResolvedContractDescription {
            val contract = this@withReturnsResultOf
            source = contract?.source
            contract?.let {
                effects += it.effects
                unresolvedEffects += it.unresolvedEffects
                diagnostic = it.diagnostic
            }
            for ((index, name) in parameters) {
                effects += buildEffectDeclaration { effect = KtReturnsResultOfDeclaration(KtValueParameterReference(index, name)) }
            }
        }

    private fun FirFunctionCall.calleeSymbol(): FirFunctionSymbol<*>? =
        (calleeReference.toResolvedCallableSymbol()
            ?: (calleeReference as? FirNamedReferenceWithCandidate)?.candidateSymbol) as? FirFunctionSymbol<*>
}
