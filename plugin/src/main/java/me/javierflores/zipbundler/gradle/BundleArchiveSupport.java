package me.javierflores.zipbundler.gradle;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class BundleArchiveSupport {
    static final String INTERNAL_CONTAINER_KEY = "__container";

    private BundleArchiveSupport() {
    }

    static ArchiveMetadata writeZip(
        List<Path> sourceDirectories,
        Path outputZip,
        String bundlePath,
        List<String> requiredFiles,
        boolean allowEmpty
    ) throws IOException {
        Map<String, Path> entries = collectEntries(sourceDirectories, bundlePath);
        long regularFiles = entries.values().stream().filter(path -> !Files.isDirectory(path)).count();
        if (!allowEmpty && regularFiles == 0) {
            throw new IllegalArgumentException("Bundle '" + bundlePath + "' must not be empty");
        }
        for (String configuredPath : requiredFiles) {
            String requiredPath = ZipBundlerPlugin.normalizePath(configuredPath);
            ZipBundlerPlugin.validateRelativePath("requiredFile", requiredPath, bundlePath);
            Path source = entries.get(requiredPath);
            if (source == null || Files.isDirectory(source)) {
                throw new IllegalArgumentException(
                    "Bundle '" + bundlePath + "' requires file '" + requiredPath + "' at its root"
                );
            }
        }

        Files.createDirectories(outputZip.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outputZip)))) {
            for (Map.Entry<String, Path> entry : entries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).toList()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                if (!Files.isDirectory(entry.getValue())) {
                    Files.copy(entry.getValue(), zip);
                }
                zip.closeEntry();
            }
        }
        return new ArchiveMetadata(outputZip, sha1(outputZip), Files.size(outputZip));
    }

    static void writeFragment(
        Path outputFile,
        String containerName,
        Map<String, BundleMetadata> bundles
    ) throws IOException {
        Map<String, String> properties = new TreeMap<>();
        properties.put(INTERNAL_CONTAINER_KEY, containerName);
        bundles.forEach((bundleName, metadata) -> {
            String prefix = "bundle." + bundleName + ".";
            properties.put(prefix + "path", metadata.archivePath());
            properties.put(prefix + "sha1", metadata.sha1());
            properties.put(prefix + "size", Long.toString(metadata.size()));
        });
        WriteZipBundleMetadata.writeProperties(outputFile, properties, "Internal generated bundle metadata");
    }

    private static Map<String, Path> collectEntries(List<Path> sourceDirectories, String bundlePath) throws IOException {
        Map<String, Path> entries = new LinkedHashMap<>();
        for (Path sourceDirectory : sourceDirectories) {
            try (var paths = Files.walk(sourceDirectory)) {
                for (Path path : paths.sorted().toList()) {
                    if (path.equals(sourceDirectory)) {
                        continue;
                    }
                    String entryName = sourceDirectory.relativize(path).toString().replace('\\', '/');
                    if (Files.isDirectory(path)) {
                        entryName += "/";
                    }
                    Path previous = entries.putIfAbsent(entryName, path);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                            "Duplicate entry '" + entryName + "' while bundling '" + bundlePath + "' from " + previous + " and " + path
                        );
                    }
                }
            }
        }
        return entries;
    }

    private static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java implementation must provide SHA-1", exception);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    record ArchiveMetadata(Path file, String sha1, long size) {
    }

    record BundleMetadata(String archivePath, String sha1, long size) {
    }
}
