package io.github.tke.shush

import io.github.tke.shush.compat.ShushCompatLayers
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals

/**
 * KEFS must see the jar as a multi-release compiler plugin jar listing exactly the compilers we have a layer for.
 * Mirrors KEFS's `KefsDevKit` detection.
 */
class KefsJarTest {
    private val jar = File(System.getProperty("shush.test.pluginJar") ?: error("shush.test.pluginJar is not set"))

    private val versionEntry = "^META-INF/kotlin/plugin/([^/]+)/versions/([^/]+)/".toRegex()

    @Test
    fun `the jar is a multi-release compiler plugin jar for every compatibility layer`() {
        JarFile(jar).use { jarFile ->
            assertEquals("true", jarFile.manifest?.mainAttributes?.getValue("Kotlin-Compiler-Plugin-Multi-Release"))

            val versionsByPlugin = jarFile.entries().asSequence()
                .mapNotNull { versionEntry.find(it.name)?.destructured }
                .groupBy({ (pluginId, _) -> pluginId }, { (_, version) -> version })
                .mapValues { (_, versions) -> versions.toSortedSet() }

            assertEquals(
                mapOf(BuildConfig.KOTLIN_PLUGIN_ID to ShushCompatLayers.versions.map { it.toString() }.toSortedSet()),
                versionsByPlugin,
            )
        }
    }
}
