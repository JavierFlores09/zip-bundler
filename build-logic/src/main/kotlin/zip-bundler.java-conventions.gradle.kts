import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test

plugins {
    java
}

group = "me.javierflores"
version = "0.1.0-SNAPSHOT"

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
