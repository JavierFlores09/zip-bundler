package me.javierflores.zipbundler.runtime;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Discovers and opens ZIP bundles produced by the {@code me.javierflores.zipbundler} Gradle plugin.
 *
 * <p>The catalog is immutable and safe to query concurrently. Streams returned by
 * {@link #openBundle(String, String)} or {@link #openBundle(BundleInfo)} must be closed
 * before this catalog is closed.</p>
 */
public final class ZipBundleCatalog implements AutoCloseable {
    public static final int SUPPORTED_FORMAT_VERSION = 1;
    public static final String MANIFEST_PATH = "META-INF/zip-bundler/manifest.properties";

    private final ResourceReader resources;
    private final int formatVersion;
    private final Map<String, Map<String, BundleInfo>> containers;

    private ZipBundleCatalog(ResourceReader resources) throws IOException {
        this.resources = resources;
        try {
            LoadedCatalog loaded = loadCatalog(resources);
            this.formatVersion = loaded.formatVersion();
            this.containers = loaded.containers();
        } catch (IOException | RuntimeException | Error throwable) {
            try {
                resources.close();
            } catch (Throwable closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            throw throwable;
        }
    }

    /** Opens metadata and bundles from either a JAR file or an exploded resource directory. */
    public static ZipBundleCatalog open(Path jarOrDirectory) throws IOException {
        Objects.requireNonNull(jarOrDirectory, "jarOrDirectory");
        Path path = jarOrDirectory.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            return new ZipBundleCatalog(new DirectoryResourceReader(path));
        }
        return new ZipBundleCatalog(new JarResourceReader(new ZipFile(path.toFile())));
    }

    /**
     * Opens the exact artifact or classes directory containing {@code anchor}.
     * For a Paper plugin, pass a class owned by the plugin rather than a dependency class.
     */
    public static ZipBundleCatalog open(Class<?> anchor) throws IOException {
        Objects.requireNonNull(anchor, "anchor");
        CodeSource codeSource = anchor.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IOException("Cannot determine the code source for " + anchor.getName());
        }
        try {
            return open(Path.of(codeSource.getLocation().toURI()));
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Cannot resolve the code source for " + anchor.getName(), exception);
        }
    }

    /**
     * Opens resources through a classloader. This is useful for plugin-scoped classloaders;
     * use {@link #open(Path)} when the classloader may expose metadata from multiple JARs.
     */
    public static ZipBundleCatalog open(ClassLoader classLoader) throws IOException {
        return new ZipBundleCatalog(new ClassLoaderResourceReader(Objects.requireNonNull(classLoader, "classLoader")));
    }

    /** Returns container names in deterministic order. */
    public Set<String> containers() {
        return containers.keySet();
    }

    public int formatVersion() {
        return formatVersion;
    }

    /** Returns bundle metadata by name for a container. */
    public Map<String, BundleInfo> bundles(String containerName) {
        Map<String, BundleInfo> bundles = containers.get(containerName);
        if (bundles == null) {
            throw new NoSuchElementException("Unknown ZIP bundle container: " + containerName);
        }
        return bundles;
    }

    /** Returns the embedded JAR path for a bundle. */
    public String bundlePath(String containerName, String bundleName) {
        return bundleInfo(containerName, bundleName).path();
    }

    /** Returns the embedded JAR path for a catalog bundle. */
    public String bundlePath(BundleInfo bundle) {
        return requireCatalogBundle(bundle).path();
    }

    public BundleInfo bundleInfo(String containerName, String bundleName) {
        BundleInfo info = bundles(containerName).get(bundleName);
        if (info == null) {
            throw new NoSuchElementException(
                "Unknown ZIP bundle '" + bundleName + "' in container '" + containerName + "'"
            );
        }
        return info;
    }

    /** Opens the embedded ZIP bytes without extracting them. */
    public InputStream openBundle(String containerName, String bundleName) throws IOException {
        return openBundle(bundleInfo(containerName, bundleName));
    }

    /** Opens the embedded ZIP bytes without extracting them. */
    public InputStream openBundle(BundleInfo bundle) throws IOException {
        return resources.open(bundlePath(bundle));
    }

    /** Reads the embedded ZIP and verifies its recorded size and SHA-1 content identity. */
    public byte[] readBundle(String containerName, String bundleName) throws IOException {
        return readBundle(bundleInfo(containerName, bundleName));
    }

    /** Reads the embedded ZIP and verifies its recorded size and SHA-1 content identity. */
    public byte[] readBundle(BundleInfo bundle) throws IOException {
        BundleInfo info = requireCatalogBundle(bundle);
        MessageDigest digest = sha1Digest();
        byte[] data;
        try (InputStream input = new DigestInputStream(resources.open(info.path()), digest)) {
            data = input.readAllBytes();
        }
        String actualSha1 = HexFormat.of().formatHex(digest.digest());
        if (data.length != info.size() || !actualSha1.equals(info.sha1())) {
            throw new IOException(
                "ZIP bundle '" + info.name() + "' in container '" + info.containerName()
                    + "' does not match its recorded size and SHA-1"
            );
        }
        return data;
    }

    /** Reads the bundle and verifies both its recorded size and SHA-1 content identity. */
    public boolean verifyBundle(String containerName, String bundleName) throws IOException {
        return verifyBundle(bundleInfo(containerName, bundleName));
    }

    /** Reads the bundle and verifies both its recorded size and SHA-1 content identity. */
    public boolean verifyBundle(BundleInfo bundle) throws IOException {
        BundleInfo info = requireCatalogBundle(bundle);
        MessageDigest digest = sha1Digest();
        long size = 0;
        try (InputStream input = resources.open(info.path())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        return size == info.size() && HexFormat.of().formatHex(digest.digest()).equals(info.sha1());
    }

    /** Copies the embedded ZIP itself to {@code targetZip}. */
    public Path copyBundle(String containerName, String bundleName, Path targetZip, CopyOption... options)
        throws IOException {
        return copyBundle(bundleInfo(containerName, bundleName), targetZip, options);
    }

    /** Copies the embedded ZIP itself to {@code targetZip}. */
    public Path copyBundle(BundleInfo bundle, Path targetZip, CopyOption... options) throws IOException {
        BundleInfo info = requireCatalogBundle(bundle);
        Objects.requireNonNull(targetZip, "targetZip");
        Path parent = targetZip.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream input = resources.open(info.path())) {
            Files.copy(input, targetZip, options);
        }
        return targetZip;
    }

    /**
     * Extracts a bundle into {@code targetDirectory}. Existing files are not replaced unless
     * a replacement option such as {@code StandardCopyOption.REPLACE_EXISTING} is supplied.
     */
    public Path extractBundle(String containerName, String bundleName, Path targetDirectory, CopyOption... options)
        throws BundleExtractionException {
        return extractBundle(bundleInfo(containerName, bundleName), targetDirectory, options);
    }

    /**
     * Extracts a bundle into {@code targetDirectory}. Existing files are not replaced unless
     * a replacement option such as {@code StandardCopyOption.REPLACE_EXISTING} is supplied.
     */
    public Path extractBundle(BundleInfo bundle, Path targetDirectory, CopyOption... options)
        throws BundleExtractionException {
        BundleInfo info = requireCatalogBundle(bundle);
        Objects.requireNonNull(targetDirectory, "targetDirectory");
        Path root = targetDirectory.toAbsolutePath().normalize();
        String currentEntry = null;
        try {
            rejectSymbolicLink(root);
            Files.createDirectories(root);
            try (ZipInputStream zip = new ZipInputStream(resources.open(info.path()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    currentEntry = entry.getName();
                    String entryName = entry.getName().replace('\\', '/');
                    Path output = root.resolve(entryName).normalize();
                    if (entryName.startsWith("/") || !output.startsWith(root)) {
                        throw new IOException("ZIP bundle contains an entry outside the target directory: " + entry.getName());
                    }
                    rejectSymbolicLinks(root, output);
                    if (entry.isDirectory()) {
                        Files.createDirectories(output);
                    } else {
                        Path parent = output.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(zip, output, options);
                    }
                    zip.closeEntry();
                }
            }
        } catch (IOException exception) {
            throw new BundleExtractionException(
                info.containerName(),
                info.name(),
                root,
                currentEntry,
                exception
            );
        }
        return targetDirectory;
    }

    private static void rejectSymbolicLinks(Path root, Path output) throws IOException {
        Path current = root;
        for (Path segment : root.relativize(output)) {
            current = current.resolve(segment);
            rejectSymbolicLink(current);
        }
    }

    private static void rejectSymbolicLink(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("ZIP bundle extraction path contains a symbolic link: " + path);
        }
    }

    @Override
    public void close() throws IOException {
        resources.close();
    }

    private static LoadedCatalog loadCatalog(ResourceReader resources) throws IOException {
        Properties manifest = loadProperties(resources, MANIFEST_PATH);
        int formatVersion;
        try {
            formatVersion = Integer.parseInt(requiredProperty(manifest, "formatVersion", MANIFEST_PATH));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid ZIP Bundler formatVersion", exception);
        }
        if (formatVersion != SUPPORTED_FORMAT_VERSION) {
            throw new IOException(
                "Unsupported ZIP Bundler metadata format " + formatVersion + "; supported version is " + SUPPORTED_FORMAT_VERSION
            );
        }
        String containersPath = validateResourcePath(requiredProperty(manifest, "containers", MANIFEST_PATH));
        Properties containerIndex = loadProperties(resources, containersPath);
        Map<String, Map<String, BundleInfo>> result = new TreeMap<>();
        for (String containerName : containerIndex.stringPropertyNames()) {
            String descriptorPath = validateResourcePath(containerIndex.getProperty(containerName));
            Properties descriptor = loadProperties(resources, descriptorPath);
            Map<String, BundleInfo> bundles = new TreeMap<>();
            for (String key : descriptor.stringPropertyNames()) {
                if (!key.startsWith("bundle.") || !key.endsWith(".path")) {
                    continue;
                }
                String prefix = key.substring(0, key.length() - "path".length());
                String bundleName = key.substring("bundle.".length(), key.length() - ".path".length());
                String path = validateResourcePath(descriptor.getProperty(key));
                String sha1 = requiredProperty(descriptor, prefix + "sha1", descriptorPath);
                long size;
                try {
                    size = Long.parseLong(requiredProperty(descriptor, prefix + "size", descriptorPath));
                } catch (NumberFormatException exception) {
                    throw new IOException("Invalid bundle size for '" + bundleName + "' in " + descriptorPath, exception);
                }
                try {
                    bundles.put(bundleName, new BundleInfo(containerName, bundleName, path, sha1, size));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid metadata for bundle '" + bundleName + "' in " + descriptorPath, exception);
                }
            }
            result.put(containerName, immutableOrderedMap(bundles));
        }
        return new LoadedCatalog(formatVersion, immutableOrderedMap(result));
    }

    private static String requiredProperty(Properties properties, String name, String source) throws IOException {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing property '" + name + "' in " + source);
        }
        return value;
    }

    private BundleInfo requireCatalogBundle(BundleInfo bundle) {
        Objects.requireNonNull(bundle, "bundle");
        BundleInfo catalogBundle = bundleInfo(bundle.containerName(), bundle.name());
        if (!catalogBundle.equals(bundle)) {
            throw new IllegalArgumentException(
                "Bundle metadata does not match catalog entry '" + bundle.name()
                    + "' in container '" + bundle.containerName() + "'"
            );
        }
        return catalogBundle;
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java implementation must provide SHA-1", exception);
        }
    }

    private static Properties loadProperties(ResourceReader resources, String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = resources.open(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String validateResourcePath(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("Bundle metadata contains an empty resource path");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("Bundle metadata contains an absolute resource path: " + path);
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Bundle metadata contains an invalid resource path: " + path);
            }
        }
        return normalized;
    }

    private static <K, V> Map<K, V> immutableOrderedMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    @FunctionalInterface
    private interface ResourceReader extends Closeable {
        InputStream open(String path) throws IOException;

        @Override
        default void close() throws IOException {
        }
    }

    private record DirectoryResourceReader(Path root) implements ResourceReader {
        @Override
        public InputStream open(String path) throws IOException {
            Path resource = root.resolve(validateResourcePath(path)).normalize();
            if (!resource.startsWith(root)) {
                throw new IOException("Resource escapes the artifact directory: " + path);
            }
            return Files.newInputStream(resource);
        }
    }

    private record JarResourceReader(ZipFile jar) implements ResourceReader {
        @Override
        public InputStream open(String path) throws IOException {
            ZipEntry entry = jar.getEntry(validateResourcePath(path));
            if (entry == null || entry.isDirectory()) {
                throw new FileNotFoundException("Resource is not present in " + jar.getName() + ": " + path);
            }
            return jar.getInputStream(entry);
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    private record ClassLoaderResourceReader(ClassLoader classLoader) implements ResourceReader {
        @Override
        public InputStream open(String path) throws IOException {
            String resourcePath = validateResourcePath(path);
            InputStream input = classLoader.getResourceAsStream(resourcePath);
            if (input == null) {
                throw new FileNotFoundException("Classloader resource is not present: " + resourcePath);
            }
            return input;
        }
    }

    private record LoadedCatalog(int formatVersion, Map<String, Map<String, BundleInfo>> containers) {
    }
}
