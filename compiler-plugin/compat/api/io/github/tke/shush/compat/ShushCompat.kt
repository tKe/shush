package io.github.tke.shush.compat

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement

/**
 * Compiler APIs whose binary signature differs between supported compilers. Each implementation ("layer") is a
 * separate compilation against the oldest compiler it supports; `ShushCompatLayers` picks one at runtime.
 */
interface ShushCompat {
    /** `KtSourceElement.fakeElement(kind)`: a top-level extension up to Kotlin 2.4, a member from 2.5. */
    fun fakeElement(source: KtSourceElement, kind: KtFakeSourceElementKind): KtSourceElement
}
