package io.github.tke.shush

import de.infix.testBalloon.framework.core.testSuite
import io.github.tke.shush.compat.ShushCompatLayers
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals

/**
 * KEFS must see the jar as a multi-release compiler plugin jar listing exactly the compilers we have a layer for.
 * Mirrors KEFS's `KefsDevKit` detection.
 */
val KefsJarTest by testSuite {
    val jar = File(System.getProperty("shush.test.pluginJar") ?: error("shush.test.pluginJar is not set"))

    val versionEntry = "^META-INF/kotlin/plugin/([^/]+)/versions/([^/]+)/".toRegex()

    testIO("the jar is a multi-release compiler plugin jar for every compatibility layer") {
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

    testIO("the jar declares the IDE builds IntelliJ loads it on without KEFS") {
        JarFile(jar).use { jarFile ->
            val attributes = jarFile.manifest?.mainAttributes
            assertEquals("263", attributes?.getValue("Kotlin-Compiler-Plugin-Since-Build"))
            assertEquals("263.*", attributes?.getValue("Kotlin-Compiler-Plugin-Until-Build"))
        }
    }
}
