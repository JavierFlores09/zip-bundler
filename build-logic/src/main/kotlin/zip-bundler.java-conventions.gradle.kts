import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test

plugins {
    java
}

group = "me.javierflores"
version = providers.environmentVariable("ZIPBUNDLER_VERSION").orNull
    ?: throw GradleException("The ZIPBUNDLER_VERSION environment variable must be set.")

repositories {
    mavenCentral()
}

val libraries = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libraries.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
