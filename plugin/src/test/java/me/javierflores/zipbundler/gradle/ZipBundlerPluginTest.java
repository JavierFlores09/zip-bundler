package me.javierflores.zipbundler.gradle;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipBundlerPluginTest {
    @Test
    void appliesJavaAndRegistersTheExtensionAndMetadataTask() {
        Project project = ProjectBuilder.builder().build();

        project.getPlugins().apply("me.javierflores.zipbundler");

        assertTrue(project.getPlugins().hasPlugin(JavaPlugin.class));
        ZipBundlerExtension extension = project.getExtensions().getByType(ZipBundlerExtension.class);
        assertNotNull(extension.getBundles());
        assertNotNull(extension.getContainers());
        assertNotNull(project.getTasks().findByName("generateZipBundleMetadata"));
        assertNotNull(project.getTasks().findByName("initZipBundleDirectories"));
    }

    @Test
    void lazilySelectsExplicitBundlesByContainer() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("me.javierflores.zipbundler");
        ZipBundlerExtension extension = project.getExtensions().getByType(ZipBundlerExtension.class);
        ZipBundleContainer packs = extension.getContainers().create("packs");

        var byContainer = extension.getBundles(packs);
        extension.getBundles().create("root_bundle");
        extension.getBundles().create("contained_bundle", bundle -> bundle.getContainer().set("packs"));

        assertEquals(
            java.util.List.of("contained_bundle"),
            byContainer.get().stream().map(ZipBundle::getName).toList()
        );
        assertEquals(byContainer.get(), extension.getBundles("packs").get());
    }
}
