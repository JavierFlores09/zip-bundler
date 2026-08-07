package me.javierflores.zipbundler.resourcepack.gradle;

import me.javierflores.zipbundler.gradle.ZipBundlerExtension;
import me.javierflores.zipbundler.gradle.ZipBundlerPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Conventions for embedding resource packs with ZIP Bundler. */
public final class ResourcePackConventionPlugin implements Plugin<Project> {
    public static final String CONTAINER_NAME = "resource_packs";
    public static final String PACK_METADATA_PATH = "pack.mcmeta";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(ZipBundlerPlugin.class);

        ZipBundlerExtension extension = project.getExtensions().getByType(ZipBundlerExtension.class);
        var resourcePacks = extension.getContainers().maybeCreate(CONTAINER_NAME);
        resourcePacks.rejectEmpty();
        resourcePacks.requireFile(PACK_METADATA_PATH);
    }
}
