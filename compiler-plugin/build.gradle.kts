import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.tapmoc)
    alias(libs.plugins.maven.publish)
    `java-test-fixtures`
    idea
}

description = "K2 compiler plugin that suppresses chosen compiler warnings in chosen places"

val testDataDir = layout.projectDirectory.dir("testData")
val testGenDirectory = layout.buildDirectory.dir("test-gen")

/**
 * Compiler (and test framework) version the plugin is compiled and tested against. Defaults to the KGP version;
 * `-PkotlinCompilerVersion` swaps it without changing the KGP that builds this project.
 */
val kotlinCompilerVersion: String = providers.gradleProperty("kotlinCompilerVersion")
    .orElse(libs.versions.kotlin)
    .get()

/**
 * Compiler (and test framework) version the tests run on. Defaults to [kotlinCompilerVersion]. Setting
 * `-PkotlinTestCompilerVersion` runs the plugin as published on another compiler, which is the binary compatibility
 * check.
 */
val kotlinTestCompilerVersion: String = providers.gradleProperty("kotlinTestCompilerVersion")
    .orElse(kotlinCompilerVersion)
    .get()

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    testFixtures {
        java.setSrcDirs(listOf("test-fixtures"))
    }
    test {
        java.setSrcDirs(listOf("test", testGenDirectory))
        resources.setSrcDirs(listOf(testDataDir))
    }
}

idea {
    // This is needed until IDEA fixes IDEA-339729.
    module.generatedSourceDirs.add(testGenDirectory.get().asFile)
}

val kotlinPluginId = "${rootProject.group}.${rootProject.name}"

val testArtifacts: Configuration = configurations.create("testArtifact")

// never pass the configurations KGP uses to run the compiler
fun swapKotlinVersion(configurationNames: Set<String>, version: String, reason: String) {
    configurations.matching { it.name in configurationNames }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(version)
                because(reason)
            }
        }
    }
}

val testConfigurations = setOf(
    "testFixturesCompileClasspath",
    "testFixturesRuntimeClasspath",
    "testCompileClasspath",
    "testRuntimeClasspath",
    testArtifacts.name,
)

// -PkotlinCompilerVersion: compile and test the plugin against another compiler.
if (kotlinCompilerVersion != libs.versions.kotlin.get()) {
    swapKotlinVersion(setOf("compileClasspath"), kotlinCompilerVersion, "-PkotlinCompilerVersion=$kotlinCompilerVersion")
}
// -PkotlinTestCompilerVersion: the compiler the tests run on, whatever main was compiled against.
if (kotlinTestCompilerVersion != libs.versions.kotlin.get()) {
    swapKotlinVersion(testConfigurations, kotlinTestCompilerVersion, "Tests run on Kotlin $kotlinTestCompilerVersion")
}

/*
 * Compat layers (see ShushCompat), all packaged into the one jar:
 *   compatApi (oldest supported compiler) <- compatK24 (2.4.20), compatK25 (2.5.x) <- main
 *
 * main associates with compatApi only. Associating with a layer would inherit its dependencies, and the 2.5 compiler
 * would win conflict resolution, compiling main (and its inlined FIR builders) against 2.5. main only needs the
 * layers' classes to construct them.
 */
// Layer compilation -> oldest compiler it supports. Keep in sync with ShushCompatLayers.
val compatLayers = mapOf("compatK24" to "2.4.20", "compatK25" to "2.5.0")

kotlin {
    val compatApi = target.compilations.create("compatApi")
    compatLayers.keys.forEach { target.compilations.create(it) { associateWith(compatApi) } }
    target.compilations.getByName("main") { associateWith(compatApi) }
}

sourceSets {
    named("compatApi") { kotlin.setSrcDirs(listOf("compat/api")) }
    compatLayers.forEach { (name, version) -> named(name) { kotlin.setSrcDirs(listOf("compat/k$version")) } }
}

val compatLayerSourceSets = compatLayers.keys.map { sourceSets.named(it) }
val compatSourceSets = listOf(sourceSets.named("compatApi")) + compatLayerSourceSets

dependencies {
    // Main runs on every supported compiler, so it's compiled against the oldest one.
    compileOnly(libs.kotlin.compiler.oldestSupported)
    "compatApiCompileOnly"(libs.kotlin.compiler.oldestSupported)
    "compatK24CompileOnly"(libs.kotlin.compiler.layer.k24)
    "compatK25CompileOnly"(libs.kotlin.compiler.layer.k25)
    compatLayerSourceSets.forEach { compileOnly(files(it.map { set -> set.output.classesDirs })) }
    compatSourceSets.forEach { testRuntimeOnly(files(it.map { set -> set.output.classesDirs })) }

    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.compiler.test.framework)
    testFixturesApi(libs.kotlin.compiler)
    testFixturesRuntimeOnly(libs.junit4)

    testImplementation(platform(libs.junit5.bom))
    testRuntimeOnly(libs.junit.platform.launcher)

    // Dependencies required to run the internal test framework.
    testArtifacts(libs.kotlin.stdlib)
    testArtifacts(libs.kotlin.stdlib.jdk8)
    testArtifacts(libs.kotlin.reflect)
    testArtifacts(libs.kotlin.test)
    testArtifacts(libs.kotlin.script.runtime)
    testArtifacts(libs.kotlin.annotations.jvm)
}

buildConfig {
    useKotlinOutput {
        internalVisibility = true
    }
    packageName("io.github.tke.shush")
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"$kotlinPluginId\"")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

// The plugin runs on the compiler's bundled stdlib, so limit language/API version to the oldest supported compiler,
// whatever KGP builds it. No stdlib dependency is published either (see gradle.properties).
tapmoc {
    java(17)
    kotlin(libs.versions.compilerOldestSupported.get())
}

// Test fixtures are an implementation detail of the test suite; don't publish them.
(components["java"] as AdhocComponentWithVariants).apply {
    withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
    // added by the sources jar the Maven Central configuration publishes
    afterEvaluate {
        withVariantsFromConfiguration(configurations["testFixturesSourcesElements"]) { skip() }
    }
}

tasks.test {
    dependsOn(testArtifacts)

    useJUnitPlatform()
    workingDir = rootDir

    inputs.dir(testDataDir)
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("kotlinCompilerVersion", kotlinCompilerVersion)
    inputs.property("kotlinTestCompilerVersion", kotlinTestCompilerVersion)
    // so tests can check they run on the intended compiler
    systemProperty("shush.test.compilerVersion", kotlinTestCompilerVersion)
    // relative to workingDir to keep the task relocatable
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(pluginJar).withPropertyName("pluginJar").withNormalizer(ClasspathNormalizer::class)
    systemProperty("shush.test.pluginJar", pluginJar.get().asFile.relativeTo(rootDir).path)

    // Properties required to run the internal test framework.
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    // `-Pkotlin.test.update.test.data=true` rewrites test data (diagnostic markers, dumps) with the actual output.
    providers.gradleProperty("kotlin.test.update.test.data").orNull?.let { systemProperty("kotlin.test.update.test.data", it) }

    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)
}

val generateTests = tasks.register<JavaExec>("generateTests") {
    inputs.dir(testDataDir)
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(testGenDirectory)
        .withPropertyName("generatedTests")

    val outputDir = testGenDirectory.get().asFile
    doFirst { outputDir.deleteRecursively() } // drop generated tests of removed test data

    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("io.github.tke.shush.GenerateTestsKt")
    workingDir = rootDir
    args(
        listOf(
            testGenDirectory.get().asFile.absolutePath,
            testDataDir.asFile.absolutePath,
        )
    )
}

tasks.compileTestKotlin {
    dependsOn(generateTests)
    // Main is also on the test classpath as the jar (through the test fixtures' dependency on this project's
    // apiElements), which must count as a friend too for tests to see internal declarations.
    friendPaths.from(tasks.named("jar"))
}

/*
 * Compiler Plugin DevKit multi-release markers, so IntelliJ's Kotlin External FIR Support (KEFS) loads the jar as is:
 * a manifest attribute and a `META-INF/kotlin/plugin/<plugin id>/versions/<kotlin version>/` directory per layer.
 * The layers stay at the jar root, since ShushCompatLayers picks one at runtime, so those directories only hold a note.
 */
val kefsMarkers = tasks.register("kefsMarkers") {
    val pluginId = kotlinPluginId
    val layers = compatLayers.mapKeys { (name, _) -> name.removePrefix("compat") }
    val outputDir = layout.buildDirectory.dir("generated/kefs")
    inputs.property("pluginId", pluginId)
    inputs.property("layers", layers)
    outputs.dir(outputDir).withPropertyName("markers")

    doLast {
        val root = outputDir.get().asFile.apply { deleteRecursively() }
        layers.forEach { (layer, version) ->
            root.resolve("META-INF/kotlin/plugin/$pluginId/versions/$version").apply { mkdirs() }
                .resolve("README.txt")
                .writeText("Kotlin $version+ uses shush's $layer compatibility layer, selected at runtime.\n")
        }
    }
}

tasks.jar {
    compatSourceSets.forEach { from(it.map { set -> set.output.classesDirs }) }
    from(kefsMarkers)
    manifest {
        attributes("Kotlin-Compiler-Plugin-Multi-Release" to "true")
    }
}

mavenPublishing {
    // Central requires a javadoc jar; Kotlin sources have no javadoc, so it's empty
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    coordinates(artifactId = "${rootProject.name}-compiler-plugin")
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
    val path = testArtifacts.files
        .find { """$jarName-\d.*""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: return
    systemProperty(propName, path)
}
