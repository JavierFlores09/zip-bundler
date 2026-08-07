package me.javierflores.zipbundler.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** Optional validation applied to the contents of each generated bundle. */
public interface BundleValidationSpec {
    ListProperty<String> getRequiredFiles();

    Property<Boolean> getAllowEmpty();

    default void requireFile(String relativePath) {
        getRequiredFiles().add(relativePath);
    }

    default void rejectEmpty() {
        getAllowEmpty().set(false);
    }

}
