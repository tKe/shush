package io.github.tke.shush.compat.k2_5_0

import io.github.tke.shush.compat.ShushCompat
import org.jetbrains.kotlin.*

/** [ShushCompat] for Kotlin 2.5.0+. */
class ShushCompatImpl : ShushCompat {
    override fun fakeElement(source: KtSourceElement, kind: KtFakeSourceElementKind): KtSourceElement =
        source.fakeElement(kind)
}
