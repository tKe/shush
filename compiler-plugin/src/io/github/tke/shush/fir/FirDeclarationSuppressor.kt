package io.github.tke.shush.fir

import io.github.tke.shush.ShushConfiguration
import io.github.tke.shush.fir.Suppressions.isAnnotation
import io.github.tke.shush.fir.Suppressions.suppress
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * `files` and `annotatedDeclarations` targets.
 *
 * There's no per-file FIR extension point, so this hooks [needTransformStatus], which runs for every declaration in
 * the STATUS phase: annotation classes are resolved by then, their arguments aren't yet. Never transforms a status.
 */
class FirDeclarationSuppressor(
    session: FirSession,
    private val configuration: ShushConfiguration,
) : FirStatusTransformerExtension(session) {
    companion object {
        fun getFactory(configuration: ShushConfiguration): Factory =
            Factory { session -> FirDeclarationSuppressor(session, configuration) }
    }

    private val visitedFiles: MutableSet<FirFile> = ConcurrentHashMap.newKeySet()

    override fun needTransformStatus(declaration: FirDeclaration): Boolean {
        if (configuration.fileMatchers.isNotEmpty()) {
            declaration.containingFile()?.let(::suppressInFile)
        }
        val diagnostics = mutableSetOf<String>()
        for ((classId, classDiagnostics) in configuration.annotatedDeclarationDiagnostics) {
            for (annotation in declaration.annotations) {
                if (annotation.isAnnotation(classId, session)) diagnostics += classDiagnostics
            }
        }
        declaration.suppress(diagnostics, session, anchor = declaration.source)
        return false
    }

    /**
     * The IDE resolves a file's annotations lazily, so it may not be at TYPES yet; the compiler resolves it in a member's
     * STATUS too, looking for must-use annotations among a callable's containers.
     */
    private fun suppressInFile(file: FirFile) {
        if (!visitedFiles.add(file)) return
        val path = file.sourceFile?.path ?: return
        val diagnostics = configuration.fileDiagnostics(Path.of(path).toAbsolutePath().normalize())
        if (diagnostics.isEmpty()) return
        file.lazyResolveToPhase(FirResolvePhase.TYPES)
        file.suppress(diagnostics, session, anchor = file.source, useSiteTarget = AnnotationUseSiteTarget.FILE)
    }

    private fun FirDeclaration.containingFile(): FirFile? = when (val symbol = symbol) {
        is FirCallableSymbol<*> -> session.firProvider.getFirCallableContainerFile(symbol)
        is FirClassLikeSymbol<*> -> session.firProvider.getFirClassifierContainerFileIfAny(symbol)
        else -> null
    }
}
