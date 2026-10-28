package io.github.tke.shush.services

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.UnresolvedExpressionTypeAccess
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.test.frontend.fir.FirOutputArtifact
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirAnalysisHandler
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions

/**
 * Checks every annotation is resolved as the IDE expects after ANNOTATION_ARGUMENTS (`checkAnnotationIsResolved`), which
 * the compiler doesn't: a call's argument list resolved and its mapped arguments typed.
 */
class IdeLazyResolveHandler(testServices: TestServices) : FirAnalysisHandler(testServices) {
    override fun processModule(module: TestModule, info: FirOutputArtifact) {
        for (part in info.partsForDependsOnModules) {
            for (file in part.firFilesByTestFile.values) {
                file.accept(ResolvedAnnotationsChecker())
            }
        }
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {}

    private inner class ResolvedAnnotationsChecker : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        @OptIn(UnresolvedExpressionTypeAccess::class)
        override fun visitAnnotation(annotation: FirAnnotation) {
            for ((name, argument) in annotation.argumentMapping.mapping) {
                testServices.assertions.assertNotNull(argument.coneTypeOrNull) { "Untyped argument $name of $annotation" }
            }
            super.visitAnnotation(annotation)
        }

        override fun visitAnnotationCall(annotationCall: FirAnnotationCall) {
            testServices.assertions.assertTrue(annotationCall.argumentList is FirResolvedArgumentList) {
                "Unresolved argument list ${annotationCall.argumentList::class.simpleName} of $annotationCall"
            }
            visitAnnotation(annotationCall)
        }
    }
}
