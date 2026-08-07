package me.javierflores.zipbundler.runtime;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Indicates that a bundle could not be completely extracted.
 * The target directory may contain entries written before the failure.
 */
public final class BundleExtractionException extends IOException {
    private final String containerName;
    private final String bundleName;
    private final Path targetDirectory;
    private final String entryName;

    BundleExtractionException(
        String containerName,
        String bundleName,
        Path targetDirectory,
        String entryName,
        IOException cause
    ) {
        super(message(containerName, bundleName, targetDirectory, entryName), cause);
        this.containerName = containerName;
        this.bundleName = bundleName;
        this.targetDirectory = targetDirectory;
        this.entryName = entryName;
    }

    public String containerName() {
        return containerName;
    }

    public String bundleName() {
        return bundleName;
    }

    public Path targetDirectory() {
        return targetDirectory;
    }

    /** Returns the failing ZIP entry, or {@code null} if extraction failed before reading an entry. */
    public String entryName() {
        return entryName;
    }

    private static String message(String containerName, String bundleName, Path targetDirectory, String entryName) {
        String entry = entryName == null ? " before reading a ZIP entry" : " at ZIP entry '" + entryName + "'";
        return "Failed to extract bundle '" + containerName + "/" + bundleName + "' to " + targetDirectory + entry
            + "; the target directory may contain partial contents";
    }
}
