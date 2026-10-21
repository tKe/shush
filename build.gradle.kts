plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.tapmoc) apply false
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
}
