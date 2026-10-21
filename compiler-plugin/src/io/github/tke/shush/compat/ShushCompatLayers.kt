package io.github.tke.shush.compat

import org.jetbrains.kotlin.KtSourceElement
import io.github.tke.shush.compat.k2_4_20.ShushCompatImpl as ShushCompatK24
import io.github.tke.shush.compat.k2_5_0.ShushCompatImpl as ShushCompatK25

/** Selects the [ShushCompat] layer for the running compiler. */
object ShushCompatLayers {
    /** Newest first. A layer's class is only linked when its lambda runs, i.e. on a compiler it supports. */
    private val layers: List<Pair<KotlinVersion, () -> ShushCompat>> = listOf(
        KotlinVersion(2, 5, 0) to { ShushCompatK25() },
        KotlinVersion(2, 4, 20) to { ShushCompatK24() },
    )

    val current: ShushCompat by lazy { load(runningCompilerVersion()) }

    fun load(compilerVersion: String): ShushCompat {
        val version = parseVersion(compilerVersion)
        val create = layers.firstOrNull { (minimum, _) -> version >= minimum }?.second
            ?: error("shush requires Kotlin ${layers.last().first} or newer, but the compiler is $compilerVersion")
        return create()
    }

    /** Not `KotlinCompilerVersion.VERSION`: it's a constant, inlined with the version we compiled against. */
    private fun runningCompilerVersion(): String =
        KtSourceElement::class.java.classLoader
            ?.getResourceAsStream("META-INF/compiler.version")
            ?.bufferedReader()
            ?.use { it.readText().trim() }
            ?: error("Could not determine the Kotlin compiler version")

    /** `2.5.0-Beta1` → `2.5.0`. */
    private fun parseVersion(version: String): KotlinVersion {
        val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        return KotlinVersion(parts[0], parts[1], parts[2])
    }
}
