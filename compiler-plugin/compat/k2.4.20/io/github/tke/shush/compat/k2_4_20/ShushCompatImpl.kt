package io.github.tke.shush.compat.k2_4_20

import io.github.tke.shush.compat.ShushCompat
import org.jetbrains.kotlin.*

/** [ShushCompat] for Kotlin 2.4.20+. */
class ShushCompatImpl : ShushCompat {
    override fun fakeElement(source: KtSourceElement, kind: KtFakeSourceElementKind): KtSourceElement =
        source.fakeElement(kind)
}
