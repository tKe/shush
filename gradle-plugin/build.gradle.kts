import org.gradle.plugin.compatibility.compatibility

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.tapmoc)
    alias(libs.plugins.plugin.publish)
    alias(libs.plugins.maven.publish)
}

description = "Gradle plugin for shush, the K2 compiler plugin that suppresses chosen compiler warnings"

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        java.setSrcDirs(listOf("test"))
        resources.setSrcDirs(listOf("testResources"))
    }
}

dependencies {
    // provided at runtime by the consumer's Kotlin Gradle plugin
    compileOnly(libs.kotlin.gradle.plugin.api)

    testImplementation(platform(libs.junit5.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)
}

buildConfig {
    useKotlinOutput {
        internalVisibility = true
    }
    packageName("io.github.tke.shush.gradle")

    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.group}.${rootProject.name}\"")

    val pluginProject = project(":compiler-plugin")
    buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${pluginProject.group}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${rootProject.name}-compiler-plugin\"")
    buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${pluginProject.version}\"")
}

kotlin {
    jvmToolchain(17)
}

// oldest supported Gradle; also run by the functional tests
val minGradleVersion = "9.3.1"

// Target the oldest supported Gradle's JVM and embedded Kotlin. No stdlib dependency (see gradle.properties).
tapmoc {
    gradle(minGradleVersion)
}

gradlePlugin {
    website = "https://github.com/tKe/shush"
    vcsUrl = "https://github.com/tKe/shush"
    plugins {
        create("shush") {
            id = "${rootProject.group}.${rootProject.name}"
            displayName = "shush"
            description = "Rules-based suppression of Kotlin compiler warnings (Mokkery templates, generated code, ...)"
            implementationClass = "io.github.tke.shush.gradle.ShushGradlePlugin"
            tags = listOf("kotlin", "compiler-plugin", "warnings", "suppress", "mokkery")
            // shown on the Plugin Portal; both are covered by the functional tests
            compatibility {
                features {
                    configurationCache = true
                    isolatedProjects = true
                }
            }
        }
    }
}

// the plugin-publish plugin adds the sources and javadoc jars, also used for Maven Central
mavenPublishing {
    coordinates(artifactId = "${rootProject.name}-gradle-plugin")
}

// the functional tests' builds resolve both plugins from here
val functionalTestRepository = rootProject.layout.buildDirectory.dir("functional-repo")

val compilerPluginJar: Configuration = configurations.create("compilerPluginJar") {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

dependencies {
    compilerPluginJar(project(":compiler-plugin"))
}

tasks.test {
    useJUnitPlatform()
    dependsOn(
        ":compiler-plugin:publishAllPublicationsToFunctionalTestRepository",
        "publishAllPublicationsToFunctionalTestRepository",
    )
    inputs.files(compilerPluginJar).withNormalizer(ClasspathNormalizer::class).withPropertyName("compilerPluginJar")
    inputs.files(tasks.jar).withNormalizer(ClasspathNormalizer::class).withPropertyName("gradlePluginJar")

    val repository = functionalTestRepository.map { it.asFile.absolutePath }
    val versions = mapOf(
        "shush.pluginVersion" to project.version.toString(),
        // this build's Kotlin and the newest a layer targets; override with -PfunctionalTestKotlinVersions=a;b
        "shush.kotlinVersions" to (providers.gradleProperty("functionalTestKotlinVersions").orNull
            ?: listOf(libs.versions.kotlin.get(), libs.versions.compilerLayerK25.get()).distinct().joinToString(";")),
        "shush.mokkeryVersion" to libs.versions.mokkery.get(),
        // oldest supported; the current Gradle is the one running this build
        "shush.minGradleVersion" to minGradleVersion,
        "shush.currentGradleHome" to gradle.gradleHomeDir!!.absolutePath,
    )
    inputs.properties(versions)
    systemProperty("shush.repository", repository.get())
    systemProperties(versions)
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
