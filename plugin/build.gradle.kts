plugins {
    id("zip-bundler.gradle-plugin-conventions")
}

gradlePlugin {
    plugins.create("zipBundler") {
        id = "me.javierflores.zipbundler"
        displayName = "ZIP Bundler"
        description = "Bundles selected resource directories as ZIP files inside a JAR."
        implementationClass = "me.javierflores.zipbundler.gradle.ZipBundlerPlugin"
    }
}
