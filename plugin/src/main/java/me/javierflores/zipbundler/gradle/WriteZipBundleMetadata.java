package me.javierflores.zipbundler.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

@CacheableTask
public abstract class WriteZipBundleMetadata extends DefaultTask {
    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @Input
    public abstract ListProperty<String> getContainerNames();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getMappingFragments();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void writeMetadata() throws IOException {
        Set<String> declaredContainers = new HashSet<>(getContainerNames().get());
        for (String containerName : declaredContainers) {
            ZipBundlerPlugin.validateContainerName(containerName);
            if (ZipBundlerPlugin.ROOT_CONTAINER_NAME.equals(containerName)) {
                throw new IllegalArgumentException("Container name 'root' is reserved for explicitly declared bundles");
            }
        }

        Map<String, Map<String, BundleRecord>> containerMappings = new TreeMap<>();
        declaredContainers.forEach(name -> containerMappings.put(name, new TreeMap<>()));
        Set<String> archivePaths = new HashSet<>();

        for (java.io.File mappingFile : getMappingFragments().getFiles().stream().sorted().toList()) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(mappingFile.toPath())) {
                properties.load(input);
            }
            String containerName = properties.getProperty(BundleArchiveSupport.INTERNAL_CONTAINER_KEY);
            if (containerName == null) {
                throw new IllegalArgumentException("Bundle metadata fragment has no container: " + mappingFile);
            }
            if (!ZipBundlerPlugin.ROOT_CONTAINER_NAME.equals(containerName) && !declaredContainers.contains(containerName)) {
                throw new IllegalArgumentException("Bundle metadata belongs to undeclared container '" + containerName + "'");
            }
            containerMappings.computeIfAbsent(containerName, ignored -> new TreeMap<>());
            readBundles(properties, containerName, containerMappings, archivePaths);
        }

        if (declaredContainers.isEmpty()) {
            containerMappings.computeIfAbsent(ZipBundlerPlugin.ROOT_CONTAINER_NAME, ignored -> new TreeMap<>());
        }

        Map<String, String> containers = new TreeMap<>();
        containerMappings.keySet().forEach(containerName -> containers.put(
            containerName,
            ZipBundlerPlugin.containerDescriptorPath(containerName)
        ));

        Path outputRoot = getOutputDirectory().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(outputRoot));
        Path metadataRoot = outputRoot.resolve("META-INF/zip-bundler");
        writeProperties(
            metadataRoot.resolve("manifest.properties"),
            Map.of(
                "formatVersion", Integer.toString(ZipBundlerPlugin.FORMAT_VERSION),
                "containers", ZipBundlerPlugin.CONTAINERS_PATH
            ),
            "ZIP Bundler metadata manifest"
        );
        writeProperties(metadataRoot.resolve("containers.properties"), containers, "Container name to descriptor mapping");
        for (Map.Entry<String, Map<String, BundleRecord>> container : containerMappings.entrySet()) {
            Map<String, String> descriptor = new TreeMap<>();
            container.getValue().forEach((bundleName, bundle) -> {
                String prefix = "bundle." + bundleName + ".";
                descriptor.put(prefix + "path", bundle.archivePath());
                descriptor.put(prefix + "sha1", bundle.sha1());
                descriptor.put(prefix + "size", Long.toString(bundle.size()));
            });
            writeProperties(
                metadataRoot.resolve("containers/" + container.getKey() + ".properties"),
                descriptor,
                "Bundle metadata for container " + container.getKey()
            );
        }
    }

    private static void readBundles(
        Properties properties,
        String containerName,
        Map<String, Map<String, BundleRecord>> containerMappings,
        Set<String> archivePaths
    ) {
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("bundle.") || !key.endsWith(".path")) {
                continue;
            }
            String prefix = key.substring(0, key.length() - "path".length());
            String bundleName = key.substring("bundle.".length(), key.length() - ".path".length());
            String archivePath = properties.getProperty(key);
            String sha1 = properties.getProperty(prefix + "sha1");
            String sizeValue = properties.getProperty(prefix + "size");
            if (bundleName.isEmpty() || sha1 == null || sizeValue == null) {
                throw new IllegalArgumentException("Incomplete metadata for a bundle in container '" + containerName + "'");
            }
            if (!sha1.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("Invalid SHA-1 for bundle '" + bundleName + "': " + sha1);
            }
            long size;
            try {
                size = Long.parseLong(sizeValue);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid size for bundle '" + bundleName + "': " + sizeValue, exception);
            }
            if (size < 0) {
                throw new IllegalArgumentException("Invalid negative size for bundle '" + bundleName + "'");
            }
            Map<String, BundleRecord> mappings = containerMappings.get(containerName);
            if (mappings.put(bundleName, new BundleRecord(archivePath, sha1, size)) != null) {
                throw new IllegalArgumentException(
                    "More than one bundle uses name '" + bundleName + "' in container '" + containerName + "'"
                );
            }
            if (!archivePaths.add(archivePath)) {
                throw new IllegalArgumentException("More than one bundle uses archivePath '" + archivePath + "'");
            }
        }
    }

    static void writeProperties(Path outputFile, Map<String, String> mappings, String comment) throws IOException {
        StringBuilder content = new StringBuilder("# ").append(comment).append('\n');
        mappings.forEach((key, value) -> content
            .append(escape(key, true))
            .append('=')
            .append(escape(value, false))
            .append('\n'));

        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, content, StandardCharsets.ISO_8859_1);
    }

    private static String escape(String value, boolean escapeSpace) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '=', ':', '#', '!' -> result.append('\\').append(character);
                case ' ' -> {
                    if (escapeSpace || index == 0) {
                        result.append('\\');
                    }
                    result.append(' ');
                }
                default -> {
                    if (character < 0x20 || character > 0x7e) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.toString();
    }

    private record BundleRecord(String archivePath, String sha1, long size) {
    }
}
