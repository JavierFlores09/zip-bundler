package me.javierflores.zipbundler.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipBundlerPluginFunctionalTest {
    @TempDir
    Path projectDir;

    @Test
    void failsWhenAnExplicitBundleSourceIsMissing() throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
            plugins {
                id 'me.javierflores.zipbundler'
            }
            zipBundler {
                bundles {
                    missing
                }
            }
            """);

        BuildResult result = runner("jar").buildAndFail();

        assertTrue(result.getOutput().contains(
            "No resource directory exists for explicit bundle 'missing' at missing"
        ));
    }

    @Test
    void appliesOptionalBundleValidation() throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
            plugins {
                id 'me.javierflores.zipbundler'
            }
            zipBundler {
                containers {
                    resource_packs {
                        rejectEmpty()
                        requireFile('pack.mcmeta')
                    }
                }
            }
            """);
        write("src/main/resources/resource_packs/invalid_pack/assets/file.txt", "contents");

        BuildResult result = runner("jar").buildAndFail();

        assertTrue(result.getOutput().contains(
            "requires file 'pack.mcmeta' at its root"
        ));
    }

    @Test
    void explicitBundleInheritsAndCombinesContainerValidation() throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
            plugins {
                id 'me.javierflores.zipbundler'
            }
            zipBundler {
                containers {
                    packs {
                        rejectEmpty()
                        requireFile('container.marker')
                    }
                }
                bundles {
                    example {
                        container = 'packs'
                        requireFile('bundle.marker')
                    }
                }
            }
            """);
        write("src/main/resources/packs/example/container.marker", "container");

        BuildResult result = runner("jar").buildAndFail();

        assertTrue(result.getOutput().contains(
            "requires file 'bundle.marker' at its root"
        ));
    }

    @Test
    void honorsTaskDependenciesOfGeneratedResourceDirectories() throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
            plugins {
                id 'me.javierflores.zipbundler'
            }

            def generatePacks = tasks.register('generatePacks') {
                def output = layout.buildDirectory.dir('generated-pack-resources')
                outputs.dir(output)
                doLast {
                    def pack = output.get().file('generated_packs/example/pack.mcmeta').asFile
                    pack.parentFile.mkdirs()
                    pack.text = 'generated pack'
                }
            }
            sourceSets.main.resources.srcDir(generatePacks)

            zipBundler {
                containers {
                    generated_packs {
                        rejectEmpty()
                        requireFile('pack.mcmeta')
                    }
                }
            }
            """);

        BuildResult result = runner("jar").build();

        assertEquals(SUCCESS, result.task(":generatePacks").getOutcome());
        try (ZipFile jar = new ZipFile(projectDir.resolve("build/libs/consumer.jar").toFile())) {
            assertZipEntry(jar, "generated_packs/example.zip", "pack.mcmeta", "generated pack");
        }
    }

    @Test
    void initializesConfiguredContainerDirectoriesOnDemand() throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
            plugins {
                id 'me.javierflores.zipbundler'
            }

            zipBundler {
                containers {
                    packs {
                        resourcePath = 'resource_packs'
                    }
                }
            }
            """);

        Path container = projectDir.resolve("src/main/resources/resource_packs");
        assertFalse(Files.exists(container));

        BuildResult result = runner("initZipBundleDirectories", "--configuration-cache").build();

        assertEquals(SUCCESS, result.task(":initZipBundleDirectories").getOutcome());
        assertTrue(Files.isDirectory(container));
    }

    @Test
    void rejectsArchivePathCollisionsBetweenExplicitAndDiscoveredBundles() throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
            plugins {
                id 'me.javierflores.zipbundler'
            }

            zipBundler {
                containers {
                    resource_packs
                }
                bundles {
                    animated_fonts {
                        container = 'resource_packs'
                        resourcePath = 'animated_fonts'
                        archivePath = 'other_pack.zip'
                    }
                }
            }
            """);
        write("src/main/resources/resource_packs/animated_fonts/font.json", "font");
        write("src/main/resources/resource_packs/other_pack/pack.mcmeta", "pack");

        BuildResult result = runner("jar").buildAndFail();

        assertTrue(result.getOutput().contains(
            "More than one bundle uses archivePath 'resource_packs/other_pack.zip'"
        ));
    }

    @Test
    void writesAnEmptyRootContainerWhenNothingIsConfigured() throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", "plugins { id 'me.javierflores.zipbundler' }\n");

        runner("jar").build();

        try (ZipFile jar = new ZipFile(projectDir.resolve("build/libs/consumer.jar").toFile())) {
            Properties containers = loadContainers(jar);
            assertEquals(
                "META-INF/zip-bundler/containers/root.properties",
                containers.getProperty("root")
            );
            assertEquals(1, containers.size());
            assertTrue(loadProperties(jar, containers.getProperty("root")).isEmpty());
        }
    }

    @Test
    void bundlesResourceDirectoriesAndWritesAnIndex() throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
            plugins {
                id 'me.javierflores.zipbundler'
            }

            zipBundler {
                bundles {
                    docs
                    schemas {
                        resourcePath = 'definitions/schemas'
                        archivePath = 'embedded/schema-data.zip'
                    }
                }
            }
            """);
        write("src/main/resources/docs/guide.txt", "guide contents");
        write("src/main/resources/definitions/schemas/model.json", "{\"type\":\"object\"}");
        write("src/main/resources/application.properties", "enabled=true");

        BuildResult result = runner("jar", "--configuration-cache").build();

        assertEquals(SUCCESS, result.task(":jar").getOutcome());
        Path jarPath = projectDir.resolve("build/libs/consumer.jar");
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("application.properties"));
            assertFalse(hasEntry(jar, "docs/"));
            assertFalse(hasEntry(jar, "definitions/schemas/"));

            assertZipEntry(jar, "docs.zip", "guide.txt", "guide contents");
            assertZipEntry(jar, "embedded/schema-data.zip", "model.json", "{\"type\":\"object\"}");

            Properties containers = loadContainers(jar);
            assertEquals(
                "META-INF/zip-bundler/containers/root.properties",
                containers.getProperty("root")
            );
            assertEquals(1, containers.size());

            Properties root = loadProperties(jar, containers.getProperty("root"));
            assertBundleMetadata(jar, root, "docs", "docs.zip");
            assertBundleMetadata(jar, root, "schemas", "embedded/schema-data.zip");
            assertEquals(6, root.size());
        }

        BuildResult cachedResult = runner("jar", "--configuration-cache").build();
        assertTrue(cachedResult.getOutput().contains("Reusing configuration cache."));
    }

    @Test
    void discoversContainerChildrenAndExposesThemThroughMainResources() throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
            plugins {
                id 'me.javierflores.zipbundler'
            }

            zipBundler {
                containers {
                    resource_packs
                }
                bundles {
                    animated_fonts {
                        container = 'resource_packs'
                        resourcePath = 'animated_fonts'
                        archivePath = 'custom/animated-fonts.zip'
                    }
                }
            }

            tasks.register('shadowLikeJar', Jar) {
                archiveClassifier = 'all'
                from sourceSets.main.output
            }
            """);
        write("src/main/resources/resource_packs/animated_fonts/assets/example/font/default.json", "animated font");
        write("src/main/resources/plugin.yml", "name: Consumer");

        BuildResult firstResult = runner("jar", "shadowLikeJar", "--configuration-cache").build();
        assertEquals(SUCCESS, firstResult.task(":jar").getOutcome());
        assertEquals(SUCCESS, firstResult.task(":shadowLikeJar").getOutcome());

        write("src/main/resources/resource_packs/other_pack/pack.mcmeta", "other pack");
        BuildResult secondResult = runner("jar", "shadowLikeJar", "--configuration-cache").build();
        assertTrue(secondResult.getOutput().contains("Reusing configuration cache."));

        assertContainerJar(projectDir.resolve("build/libs/consumer.jar"));
        assertContainerJar(projectDir.resolve("build/libs/consumer-all.jar"));
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
            .forwardOutput()
            .withPluginClasspath()
            .withArguments(arguments)
            .withProjectDir(projectDir.toFile());
    }

    private void write(String relativePath, String contents) throws IOException {
        Path file = projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    private static boolean hasEntry(ZipFile zip, String prefix) {
        return zip.stream().anyMatch(entry -> entry.getName().startsWith(prefix));
    }

    private static void assertZipEntry(ZipFile jar, String archivePath, String expectedPath, String expectedContents)
        throws IOException {
        ZipEntry archiveEntry = jar.getEntry(archivePath);
        assertNotNull(archiveEntry, archivePath + " should be present in the JAR");
        try (ZipInputStream zip = new ZipInputStream(jar.getInputStream(archiveEntry))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(expectedPath)) {
                    assertEquals(expectedContents, new String(zip.readAllBytes()));
                    return;
                }
            }
        }
        throw new AssertionError(expectedPath + " was not found in " + archivePath);
    }

    private static void assertContainerJar(Path jarPath) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("plugin.yml"));
            assertFalse(hasEntry(jar, "resource_packs/animated_fonts/"));
            assertFalse(hasEntry(jar, "resource_packs/other_pack/"));
            assertZipEntry(
                jar,
                "resource_packs/custom/animated-fonts.zip",
                "assets/example/font/default.json",
                "animated font"
            );
            assertZipEntry(jar, "resource_packs/other_pack.zip", "pack.mcmeta", "other pack");

            Properties containers = loadContainers(jar);
            assertEquals(
                "META-INF/zip-bundler/containers/resource_packs.properties",
                containers.getProperty("resource_packs")
            );
            assertEquals(1, containers.size());

            Properties metadata = loadProperties(jar, containers.getProperty("resource_packs"));
            assertBundleMetadata(jar, metadata, "animated_fonts", "resource_packs/custom/animated-fonts.zip");
            assertBundleMetadata(jar, metadata, "other_pack", "resource_packs/other_pack.zip");
            assertEquals(6, metadata.size());
        }
    }

    private static Properties loadContainers(ZipFile jar) throws IOException {
        Properties manifest = loadProperties(jar, ZipBundlerPlugin.MANIFEST_PATH);
        assertEquals("1", manifest.getProperty("formatVersion"));
        return loadProperties(jar, manifest.getProperty("containers"));
    }

    private static void assertBundleMetadata(ZipFile jar, Properties descriptor, String bundleName, String archivePath)
        throws IOException {
        String prefix = "bundle." + bundleName + ".";
        assertEquals(archivePath, descriptor.getProperty(prefix + "path"));
        ZipEntry archive = jar.getEntry(archivePath);
        assertNotNull(archive);
        byte[] bytes;
        try (InputStream input = jar.getInputStream(archive)) {
            bytes = input.readAllBytes();
        }
        assertEquals(Long.toString(bytes.length), descriptor.getProperty(prefix + "size"));
        try {
            String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
            assertEquals(sha1, descriptor.getProperty(prefix + "sha1"));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Properties loadProperties(ZipFile jar, String path) throws IOException {
        ZipEntry entry = jar.getEntry(path);
        assertNotNull(entry, path + " should be present in the JAR");
        Properties properties = new Properties();
        try (InputStream input = jar.getInputStream(entry)) {
            properties.load(input);
        }
        return properties;
    }
}
