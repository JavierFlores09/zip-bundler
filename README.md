# ZIP Bundler

A Gradle plugin for packaging resource directories as ZIP files inside a JAR. An optional runtime library can find, verify, copy, and extract those bundles.

## Usage

ZIP Bundler is published to GitHub Packages. Add the package repository to `settings.gradle.kts` for both plugin and library resolution:

```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/JavierFlores09/zip-bundler")
            credentials {
                username = providers.gradleProperty("githubPackagesUsername").orNull
                password = providers.gradleProperty("githubPackagesToken").orNull
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/JavierFlores09/zip-bundler")
            credentials {
                username = providers.gradleProperty("githubPackagesUsername").orNull
                password = providers.gradleProperty("githubPackagesToken").orNull
            }
        }
    }
}
```

GitHub Packages requires authentication, including for public packages. Put the credentials in your user-level `~/.gradle/gradle.properties` file, not in the project repository:

```properties
githubPackagesUsername=<github-username>
githubPackagesToken=<personal-access-token>
```

The token must be a classic personal access token with the `read:packages` scope.

```kotlin
plugins {
    id("me.javierflores.zipbundler") version "<version>"
}

zipBundler {
    containers {
        // Each immediate subdirectory becomes a bundle.
        create("resource_packs")
    }

    bundles {
        // A single resource directory becomes a bundle.
        create("docs")
    }
}
```

With the default layout:

```text
src/main/resources/
├── docs/
└── resource_packs/
    ├── first_pack/
    └── second_pack/
```

the JAR contains `docs.zip`, `resource_packs/first_pack.zip`, and `resource_packs/second_pack.zip`. Bundle metadata is written under `META-INF/zip-bundler`.

Generated resources work with both the standard `jar` task and Shadow's `shadowJar` task.

### Configuration

Paths and validation rules can be customized:

```kotlin
zipBundler {
    containers {
        create("packs") {
            resourcePath = "resource_packs"
            archiveDirectory = "embedded/packs"
            rejectEmpty()
            requireFile("pack.mcmeta")
        }
    }

    bundles {
        create("docs") {
            resourcePath = "documentation"
            archivePath = "embedded/docs.zip"
        }
    }
}
```

Run `./gradlew initZipBundleDirectories` to create missing container directories.

## Resource packs

The convention plugin configures a `resource_packs` container and requires each pack to be non-empty and contain `pack.mcmeta`:

```kotlin
plugins {
    id("me.javierflores.zipbundler.resource-pack-convention") version "<version>"
}
```

The regular `zipBundler` configuration remains available for additional customization.

## Runtime library

The Java 25 runtime library reads bundles from a built JAR:

```kotlin
dependencies {
    implementation("me.javierflores:zipbundler-runtime:<version>")
}
```

```java
import me.javierflores.zipbundler.runtime.ZipBundleCatalog;

try (var bundles = ZipBundleCatalog.open(MyPlugin.class)) {
    var pack = bundles.bundleInfo("resource_packs", "first_pack");

    if (!bundles.verifyBundle(pack)) {
        throw new IllegalStateException("Bundle verification failed");
    }
    bundles.extractBundle(pack, dataFolder.resolve("first_pack"));
}
```

Extraction rejects paths that escape the destination. Existing files are only replaced when `StandardCopyOption.REPLACE_EXISTING` is supplied.

Include the runtime library in the deployed JAR unless the host provides it separately.

