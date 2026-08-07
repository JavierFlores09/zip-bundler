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
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/JavierFlores09/zip-bundler")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = publicationArtifactId
            from(components["java"])
        }
    }
}
