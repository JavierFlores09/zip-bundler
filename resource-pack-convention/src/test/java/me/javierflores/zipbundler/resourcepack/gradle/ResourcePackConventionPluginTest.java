package me.javierflores.zipbundler.resourcepack.gradle;

import me.javierflores.zipbundler.gradle.ZipBundlerExtension;
import me.javierflores.zipbundler.gradle.ZipBundlerPlugin;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackConventionPluginTest {
    @Test
    void appliesZipBundlerAndConfiguresResourcePackContainerValidation() {
        Project project = ProjectBuilder.builder().build();

        project.getPlugins().apply("me.javierflores.zipbundler.resource-pack-convention");

        assertTrue(project.getPlugins().hasPlugin(ZipBundlerPlugin.class));
        ZipBundlerExtension extension = project.getExtensions().getByType(ZipBundlerExtension.class);
        var container = extension.getContainers().getByName(ResourcePackConventionPlugin.CONTAINER_NAME);
        assertEquals("resource_packs", container.getResourcePath().get());
        assertEquals("resource_packs", container.getArchiveDirectory().get());
        assertFalse(container.getAllowEmpty().get());
        assertEquals(java.util.List.of("pack.mcmeta"), container.getRequiredFiles().get());
    }
}
