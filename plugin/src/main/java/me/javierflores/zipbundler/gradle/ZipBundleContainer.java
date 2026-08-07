package me.javierflores.zipbundler.gradle;

import org.gradle.api.Named;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/** A resource directory whose immediate child directories are independent bundles. */
public abstract class ZipBundleContainer implements Named, BundleValidationSpec {
    private final String name;

    @Inject
    public ZipBundleContainer(String name) {
        this.name = name;
        getResourcePath().convention(name);
        getArchiveDirectory().convention(name);
        getRequiredFiles().convention(java.util.List.of());
        getAllowEmpty().convention(true);
    }

    @Override
    public final String getName() {
        return name;
    }

    /** Container directory relative to each main resource source directory. */
    public abstract Property<String> getResourcePath();

    /** Directory inside the output JAR that receives the generated ZIPs. */
    public abstract Property<String> getArchiveDirectory();
}
