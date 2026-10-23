package io.github.tke.shush

import de.infix.testBalloon.framework.core.testSuite
import io.github.tke.shush.compat.ShushCompatLayers
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.kotlin.KtSourceElement

val ShushCompatTest by testSuite {
    val k24 = "io.github.tke.shush.compat.k2_4_20.ShushCompatImpl"
    val k25 = "io.github.tke.shush.compat.k2_5_0.ShushCompatImpl"

    fun layerFor(version: String): String = ShushCompatLayers.load(version)::class.java.name

    fun parse(version: String): KotlinVersion {
        val (major, minor) = version.split('.', '-').map { it.toIntOrNull() ?: 0 }
        return KotlinVersion(major, minor)
    }

    test("the newest layer not newer than the compiler is loaded") {
        assertEquals(k24, layerFor("2.4.20"))
        assertEquals(k24, layerFor("2.4.21"))
        assertEquals(k24, layerFor("2.4.255-dev-1234"))
        assertEquals(k25, layerFor("2.5.0-Beta1"))
        assertEquals(k25, layerFor("2.5.0-Beta2"))
        assertEquals(k25, layerFor("2.5.0"))
        assertEquals(k25, layerFor("2.5.20"))
        assertEquals(k25, layerFor("2.6.0"))
    }

    // IntelliJ's analysis compiler reports versions like these.
    test("IDE compiler versions select a layer by their base version") {
        assertEquals(k24, layerFor("2.4.20-ij262-52"))
        assertEquals(k25, layerFor("2.5.0-dev-9507"))
    }

    test("compilers older than the oldest layer are rejected") {
        for (version in listOf("2.3.0", "2.4.0", "2.4.10")) {
            val error = assertFailsWith<IllegalStateException>(version) { ShushCompatLayers.load(version) }
            assertContains(error.message.orEmpty(), version)
            assertContains(error.message.orEmpty(), "2.4.20")
        }
    }

    // The build passes the compiler version the tests run on (see `-PkotlinTestCompilerVersion`).
    testIO("the running compiler's layer is selected") {
        val expectedVersion = System.getProperty("shush.test.compilerVersion")
            ?: error("shush.test.compilerVersion is not set")
        val runningVersion = KtSourceElement::class.java.classLoader
            .getResourceAsStream("META-INF/compiler.version")!!
            .bufferedReader().use { it.readText().trim() }
        assertEquals(expectedVersion, runningVersion, "the tests don't run on the intended compiler")

        val expectedLayer = if (KotlinVersion(2, 5) <= parse(expectedVersion)) k25 else k24
        assertEquals(expectedLayer, ShushCompatLayers.current::class.java.name)
    }
}
