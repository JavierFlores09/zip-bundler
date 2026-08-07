package me.javierflores.zipbundler.resourcepack.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackConventionPluginFunctionalTest {
    @TempDir
    Path projectDir;

    @Test
    void createsAndBundlesTheResourcePacksContainer() throws IOException {
        writeBuildFile("");
        write("src/main/resources/resource_packs/animated_fonts/pack.mcmeta", "metadata");
        write("src/main/resources/resource_packs/animated_fonts/assets/font.json", "font");

        runner("jar").build();

        try (ZipFile jar = new ZipFile(projectDir.resolve("build/libs/consumer.jar").toFile())) {
            assertNotNull(jar.getEntry("resource_packs/animated_fonts.zip"));
            assertNotNull(jar.getEntry("META-INF/zip-bundler/containers/resource_packs.properties"));
        }
    }

    @Test
    void rejectsDiscoveredPackWithoutMetadata() throws IOException {
        writeBuildFile("");
        write("src/main/resources/resource_packs/invalid_pack/assets/file.txt", "contents");

        BuildResult result = runner("jar").buildAndFail();

        assertTrue(result.getOutput().contains("requires file 'pack.mcmeta' at its root"));
    }

    @Test
    void rejectsAnEmptyExplicitPack() throws IOException {
        writeBuildFile("""
            zipBundler {
                bundles {
                    empty_pack {
                        container = 'resource_packs'
                    }
                }
            }
            """);
        Files.createDirectories(projectDir.resolve("src/main/resources/resource_packs/empty_pack"));

        BuildResult result = runner("jar").buildAndFail();

        assertTrue(result.getOutput().contains("must not be empty"));
    }

    private void writeBuildFile(String configuration) throws IOException {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        write("build.gradle", """
            plugins {
                id 'me.javierflores.zipbundler.resource-pack-convention'
            }

            %s
            """.formatted(configuration));
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(arguments)
            .withPluginClasspath();
    }

    private void write(String relativePath, String contents) throws IOException {
        Path path = projectDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }
}
