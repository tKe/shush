package io.github.tke.shush

import io.github.tke.shush.compat.ShushCompatLayers
import org.jetbrains.kotlin.KtSourceElement
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShushCompatTest {
    private val k24 = "io.github.tke.shush.compat.k2_4_20.ShushCompatImpl"
    private val k25 = "io.github.tke.shush.compat.k2_5_0.ShushCompatImpl"

    private fun layerFor(version: String): String = ShushCompatLayers.load(version)::class.java.name

    @Test
    fun `the newest layer not newer than the compiler is loaded`() {
        assertEquals(k24, layerFor("2.4.20"))
        assertEquals(k24, layerFor("2.4.21"))
        assertEquals(k24, layerFor("2.4.255-dev-1234"))
        assertEquals(k25, layerFor("2.5.0-Beta1"))
        assertEquals(k25, layerFor("2.5.0-Beta2"))
        assertEquals(k25, layerFor("2.5.0"))
        assertEquals(k25, layerFor("2.5.20"))
        assertEquals(k25, layerFor("2.6.0"))
    }

    @Test
    fun `compilers older than the oldest layer are rejected`() {
        for (version in listOf("2.3.0", "2.4.0", "2.4.10")) {
            val error = assertFailsWith<IllegalStateException>(version) { ShushCompatLayers.load(version) }
            assertContains(error.message.orEmpty(), version)
            assertContains(error.message.orEmpty(), "2.4.20")
        }
    }

    /** The build passes the compiler version the tests run on (see `-PkotlinTestCompilerVersion`). */
    @Test
    fun `the running compiler's layer is selected`() {
        val expectedVersion = System.getProperty("shush.test.compilerVersion")
            ?: error("shush.test.compilerVersion is not set")
        val runningVersion = KtSourceElement::class.java.classLoader
            .getResourceAsStream("META-INF/compiler.version")!!
            .bufferedReader().use { it.readText().trim() }
        assertEquals(expectedVersion, runningVersion, "the tests don't run on the intended compiler")

        val expectedLayer = if (KotlinVersion(2, 5) <= parse(expectedVersion)) k25 else k24
        assertEquals(expectedLayer, ShushCompatLayers.current::class.java.name)
    }

    private fun parse(version: String): KotlinVersion {
        val (major, minor) = version.split('.', '-').map { it.toIntOrNull() ?: 0 }
        return KotlinVersion(major, minor)
    }
}
