plugins {
    id("zip-bundler.gradle-plugin-conventions")
}

dependencies {
    implementation(project(":plugin"))
}

gradlePlugin {
    plugins.create("resourcePackConvention") {
        id = "me.javierflores.zipbundler.resource-pack-convention"
        displayName = "ZIP Bundler Resource Pack Convention"
        description = "Bundles resource packs and validates their required root metadata."
        implementationClass = "me.javierflores.zipbundler.resourcepack.gradle.ResourcePackConventionPlugin"
    }
}
