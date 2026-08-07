package me.javierflores.zipbundler.gradle;

import org.gradle.api.Named;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public abstract class ZipBundle implements Named, BundleValidationSpec {
    private final String name;

    @Inject
    public ZipBundle(String name) {
        this.name = name;
        getContainer().convention(ZipBundlerPlugin.ROOT_CONTAINER_NAME);
        getResourcePath().convention(name);
        getArchivePath().convention(name + ".zip");
        getRequiredFiles().convention(java.util.List.of());
        getAllowEmpty().convention(true);
    }

    @Override
    public final String getName() {
        return name;
    }

    /** Directory relative to the selected container's resource path. */
    public abstract Property<String> getResourcePath();

    /** Container name used for metadata and archive-path resolution. */
    public abstract Property<String> getContainer();

    /** Path relative to the selected container's archive directory. */
    public abstract Property<String> getArchivePath();
}
