import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.tapmoc) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.plugin.publish) apply false
}

// build-local repository for the gradle-plugin functional tests
val functionalTestRepository = layout.buildDirectory.dir("functional-repo").get().asFile
subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "functionalTest"
                url = uri(functionalTestRepository)
            }
        }
    }

    // Maven Central requirements shared by both artifacts. Credentials and the signing key come from the
    // mavenCentralUsername/-Password and signingInMemoryKey/-Password properties (see RELEASING.md).
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            // only when a key is configured, so local and functional test publishing work without one
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

            pom {
                name.set(project.provider { "${rootProject.name}-${project.name}" })
                description.set(project.provider { project.description })
                url.set("https://github.com/tKe/shush")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("tKe")
                        name.set("Phil Davies")
                        url.set("https://github.com/tKe")
                    }
                }
                scm {
                    url.set("https://github.com/tKe/shush")
                    connection.set("scm:git:https://github.com/tKe/shush.git")
                    developerConnection.set("scm:git:ssh://git@github.com/tKe/shush.git")
                }
            }
        }
    }
}
