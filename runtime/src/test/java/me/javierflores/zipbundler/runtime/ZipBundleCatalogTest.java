package me.javierflores.zipbundler.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipBundleCatalogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void listsStreamsCopiesAndExtractsBundlesFromAnExplodedDirectory() throws IOException {
        Path artifact = createArtifact(false);

        try (ZipBundleCatalog catalog = ZipBundleCatalog.open(artifact)) {
            assertEquals(Set.of("resource_packs", "root"), catalog.containers());
            assertEquals(1, catalog.formatVersion());
            BundleInfo resourcePack = catalog.bundleInfo("resource_packs", "animated_fonts");
            assertEquals("resource_packs", resourcePack.containerName());
            assertEquals("resource_packs/animated_fonts.zip", resourcePack.path());
            assertEquals("embedded/docs.zip", catalog.bundleInfo("root", "docs").path());
            assertTrue(catalog.verifyBundle(resourcePack));
            assertArrayEquals(Files.readAllBytes(artifact.resolve(resourcePack.path())), catalog.readBundle(resourcePack));

            try (InputStream bundle = catalog.openBundle(resourcePack)) {
                assertEquals("font data", readZipEntry(bundle, "assets/example/font/default.json"));
            }

            Path copiedZip = temporaryDirectory.resolve("served/animated_fonts.zip");
            catalog.copyBundle(resourcePack, copiedZip);
            try (ZipFile copied = new ZipFile(copiedZip.toFile())) {
                assertTrue(copied.getEntry("assets/example/font/default.json") != null);
            }

            Path extracted = temporaryDirectory.resolve("extracted");
            catalog.extractBundle(resourcePack, extracted);
            assertEquals(
                "font data",
                Files.readString(extracted.resolve("assets/example/font/default.json"))
            );
            Files.writeString(extracted.resolve("assets/example/font/default.json"), "old");
            BundleExtractionException existingFile = assertThrows(
                BundleExtractionException.class,
                () -> catalog.extractBundle("resource_packs", "animated_fonts", extracted)
            );
            assertEquals("resource_packs", existingFile.containerName());
            assertEquals("animated_fonts", existingFile.bundleName());
            assertEquals("assets/example/font/default.json", existingFile.entryName());
            assertEquals(extracted.toAbsolutePath(), existingFile.targetDirectory());
            catalog.extractBundle(
                "resource_packs",
                "animated_fonts",
                extracted,
                StandardCopyOption.REPLACE_EXISTING
            );
            assertEquals(
                "font data",
                Files.readString(extracted.resolve("assets/example/font/default.json"))
            );
        }
    }

    @Test
    void opensBundlesFromAJarAndAClassLoader() throws IOException {
        Path artifact = createArtifact(false);
        Path jar = temporaryDirectory.resolve("plugin.jar");
        writeJar(artifact, jar);

        try (ZipBundleCatalog catalog = ZipBundleCatalog.open(jar);
             InputStream bundle = catalog.openBundle("root", "docs")) {
            assertEquals("documentation", readZipEntry(bundle, "guide.txt"));
        }

        try (URLClassLoader classLoader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null);
             ZipBundleCatalog catalog = ZipBundleCatalog.open(classLoader);
             InputStream bundle = catalog.openBundle("resource_packs", "animated_fonts")) {
            assertEquals("font data", readZipEntry(bundle, "assets/example/font/default.json"));
        }
    }

    @Test
    void rejectsUnsupportedMetadataAndDetectsModifiedBundles() throws IOException {
        Path unsupported = createArtifact(false);
        write(
            unsupported.resolve("META-INF/zip-bundler/manifest.properties"),
            "formatVersion=2\ncontainers=META-INF/zip-bundler/containers.properties\n"
        );
        IOException versionFailure = assertThrows(IOException.class, () -> ZipBundleCatalog.open(unsupported));
        assertTrue(versionFailure.getMessage().contains("Unsupported ZIP Bundler metadata format 2"));

        Path artifact = createArtifact(false);
        try (ZipBundleCatalog catalog = ZipBundleCatalog.open(artifact)) {
            BundleInfo resourcePack = catalog.bundleInfo("resource_packs", "animated_fonts");
            BundleInfo forged = new BundleInfo(
                resourcePack.containerName(),
                resourcePack.name(),
                "resource_packs/other.zip",
                resourcePack.sha1(),
                resourcePack.size()
            );
            assertThrows(IllegalArgumentException.class, () -> catalog.openBundle(forged));
            Path resourcePackPath = artifact.resolve(resourcePack.path());
            byte[] modified = Files.readAllBytes(resourcePackPath);
            modified[modified.length - 1] ^= 1;
            Files.write(resourcePackPath, modified);
            assertFalse(catalog.verifyBundle(resourcePack));
            assertThrows(IOException.class, () -> catalog.readBundle(resourcePack));
        }
    }

    @Test
    void rejectsBlankBundleIdentityComponents() {
        String sha1 = "0".repeat(40);

        assertThrows(IllegalArgumentException.class, () -> new BundleInfo(" ", "pack", "pack.zip", sha1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BundleInfo("packs", " ", "pack.zip", sha1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BundleInfo("packs", "pack", " ", sha1, 0));
    }

    @Test
    void rejectsZipEntriesOutsideTheExtractionDirectory() throws IOException {
        Path artifact = createArtifact(true);
        Path extractionDirectory = temporaryDirectory.resolve("safe");

        try (ZipBundleCatalog catalog = ZipBundleCatalog.open(artifact)) {
            BundleExtractionException exception = assertThrows(
                BundleExtractionException.class,
                () -> catalog.extractBundle("resource_packs", "animated_fonts", extractionDirectory)
            );
            assertEquals("../escaped.txt", exception.entryName());
            assertTrue(exception.getCause().getMessage().contains("outside the target directory"));
        }
        assertFalse(Files.exists(temporaryDirectory.resolve("escaped.txt")));
        assertTrue(Files.exists(extractionDirectory.resolve("written-before-failure.txt")));

        ZipBundleFiles.clearDirectory(extractionDirectory);
        assertTrue(Files.isDirectory(extractionDirectory));
        try (var remaining = Files.list(extractionDirectory)) {
            assertEquals(0, remaining.count());
        }
        ZipBundleFiles.clearDirectory(extractionDirectory);
        ZipBundleFiles.clearDirectory(temporaryDirectory.resolve("missing"));
        assertThrows(IOException.class, () -> ZipBundleFiles.clearDirectory(temporaryDirectory.getRoot()));
    }

    @Test
    void rejectsExtractionThroughSymbolicLinks() throws IOException {
        Path artifact = createArtifact(false);
        Path extractionDirectory = Files.createDirectories(temporaryDirectory.resolve("symlink-extraction"));
        Path outsideDirectory = Files.createDirectories(temporaryDirectory.resolve("outside"));
        try {
            Files.createSymbolicLink(extractionDirectory.resolve("assets"), outsideDirectory);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        try (ZipBundleCatalog catalog = ZipBundleCatalog.open(artifact)) {
            BundleExtractionException exception = assertThrows(
                BundleExtractionException.class,
                () -> catalog.extractBundle("resource_packs", "animated_fonts", extractionDirectory)
            );
            assertTrue(exception.getCause().getMessage().contains("symbolic link"));
        }
        assertFalse(Files.exists(outsideDirectory.resolve("example/font/default.json")));
    }

    private Path createArtifact(boolean malicious) throws IOException {
        Path artifact = Files.createDirectories(temporaryDirectory.resolve(malicious ? "malicious-artifact" : "artifact"));
        write(
            artifact.resolve("META-INF/zip-bundler/manifest.properties"),
            "formatVersion=1\ncontainers=META-INF/zip-bundler/containers.properties\n"
        );
        write(
            artifact.resolve("META-INF/zip-bundler/containers.properties"),
            "resource_packs=META-INF/zip-bundler/containers/resource_packs.properties\n" +
                "root=META-INF/zip-bundler/containers/root.properties\n"
        );
        Map<String, String> resourcePackEntries;
        if (malicious) {
            resourcePackEntries = new LinkedHashMap<>();
            resourcePackEntries.put("written-before-failure.txt", "partial");
            resourcePackEntries.put("../escaped.txt", "escaped");
        } else {
            resourcePackEntries = Map.of("assets/example/font/default.json", "font data");
        }
        BundleStat resourcePack = writeEmbeddedZip(
            artifact.resolve("resource_packs/animated_fonts.zip"),
            resourcePackEntries
        );
        BundleStat docs = writeEmbeddedZip(
            artifact.resolve("embedded/docs.zip"),
            Map.of("guide.txt", "documentation")
        );
        write(
            artifact.resolve("META-INF/zip-bundler/containers/resource_packs.properties"),
            "bundle.animated_fonts.path=resource_packs/animated_fonts.zip\n" +
                "bundle.animated_fonts.sha1=" + resourcePack.sha1() + "\n" +
                "bundle.animated_fonts.size=" + resourcePack.size() + "\n"
        );
        write(
            artifact.resolve("META-INF/zip-bundler/containers/root.properties"),
            "bundle.docs.path=embedded/docs.zip\n" +
                "bundle.docs.sha1=" + docs.sha1() + "\n" +
                "bundle.docs.size=" + docs.size() + "\n"
        );
        return artifact;
    }

    private static BundleStat writeEmbeddedZip(Path zipPath, Map<String, String> entries) throws IOException {
        Files.createDirectories(zipPath.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new BundleStat(sha1(zipPath), Files.size(zipPath));
    }

    private static void writeJar(Path sourceDirectory, Path jarPath) throws IOException {
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(jarPath));
             var paths = Files.walk(sourceDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = sourceDirectory.relativize(path).toString().replace('\\', '/');
                jar.putNextEntry(new ZipEntry(name));
                Files.copy(path, jar);
                jar.closeEntry();
            }
        }
    }

    private static String readZipEntry(InputStream input, String expectedPath) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(expectedPath)) {
                    return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(expectedPath + " was not found");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record BundleStat(String sha1, long size) {
    }
}
