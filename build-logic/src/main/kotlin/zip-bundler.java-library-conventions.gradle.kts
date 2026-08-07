import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("zip-bundler.java-conventions")
    `java-library`
    `maven-publish`
}

val publicationArtifactId = "zipbundler-${project.name}"

base {
    archivesName = publicationArtifactId
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = publicationArtifactId
            from(components["java"])
        }
    }
}
