package me.javierflores.zipbundler.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class ZipBundlerPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "zipBundler";
    public static final String ROOT_CONTAINER_NAME = "root";
    public static final int FORMAT_VERSION = 1;
    public static final String MANIFEST_PATH = "META-INF/zip-bundler/manifest.properties";
    public static final String CONTAINERS_PATH = "META-INF/zip-bundler/containers.properties";
    public static final String METADATA_PATH = MANIFEST_PATH;

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        ZipBundlerExtension extension = project.getExtensions().create(
            EXTENSION_NAME,
            ZipBundlerExtension.class
        );

        var metadataTask = project.getTasks().register(
            "generateZipBundleMetadata",
            WriteZipBundleMetadata.class,
            task -> {
                task.getContainerNames().convention(java.util.List.of());
                task.getOutputDirectory().convention(
                    project.getLayout().getBuildDirectory().dir("generated/zipBundler/metadata")
                );
            }
        );

        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        var mainResources = sourceSets.getByName("main").getResources();
        ProcessResources processResources = (ProcessResources) project.getTasks().getByName(JavaPlugin.PROCESS_RESOURCES_TASK_NAME);
        var initializeDirectories = project.getTasks().register(
            "initZipBundleDirectories",
            InitializeZipBundleDirectories.class,
            task -> {
                task.setGroup("build setup");
                task.setDescription("Creates configured ZIP bundle container directories under the main resource source directories.");
            }
        );
        processResources.from(metadataTask.flatMap(WriteZipBundleMetadata::getOutputDirectory), spec -> {
            spec.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
        });

        extension.getBundles().all(bundle -> {
            String taskName = "bundle" + taskNamePart(bundle.getName()) + "Resources";
            var resolvedResourcePath = project.provider(() -> resolveResourcePath(extension, bundle));
            var resolvedArchivePath = project.provider(() -> resolveArchivePath(extension, bundle));
            var zipTask = project.getTasks().register(taskName, BundleExplicitResource.class, task -> {
                task.setGroup("build");
                task.setDescription("Bundles the '" + bundle.getName() + "' resource directory as a ZIP.");
                task.getSourceDirectories().from(mainResources.getSourceDirectories().getElements().map(elements ->
                    elements.stream()
                        .map(location -> new File(location.getAsFile(), resolvedResourcePath.get()))
                        .toList()
                ));
                task.getContainerName().set(bundle.getContainer());
                task.getBundleName().set(bundle.getName());
                task.getResourcePath().set(resolvedResourcePath);
                task.getArchivePath().set(resolvedArchivePath);
                task.getRequiredFiles().set(project.provider(() -> requiredFiles(extension, bundle)));
                task.getAllowEmpty().set(project.provider(() -> allowEmpty(extension, bundle)));
                task.getArchiveFile().convention(project.getLayout().getBuildDirectory().file(
                    resolvedArchivePath.map(path -> "generated/zipBundler/archives/" + bundle.getName() + "/" + fileName(path))
                ));
                task.getMetadataFile().convention(project.getLayout().getBuildDirectory().file(
                    "generated/zipBundler/explicit/" + bundle.getName() + "/mappings.properties"
                ));
                task.doFirst(ignored -> validateBundle(bundle, resolvedResourcePath.get(), resolvedArchivePath.get()));
            });

            mainResources.exclude(element -> belongsToPath(element, resolvedResourcePath.get()));

            processResources.from(zipTask.flatMap(BundleExplicitResource::getArchiveFile), spec -> {
                spec.into(resolvedArchivePath.map(ZipBundlerPlugin::parentPath));
                spec.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
            });
            metadataTask.configure(task -> task.getMappingFragments().from(
                zipTask.flatMap(BundleExplicitResource::getMetadataFile)
            ));
        });

        extension.getContainers().all(container -> {
            validateContainerName(container.getName());
            if (ROOT_CONTAINER_NAME.equals(container.getName())) {
                throw new IllegalArgumentException("Container name 'root' is reserved for explicitly declared bundles");
            }
            String taskName = "bundle" + taskNamePart(container.getName()) + "ResourceContainer";
            var containerTask = project.getTasks().register(taskName, BundleResourceContainer.class, task -> {
                task.setGroup("build");
                task.setDescription("Bundles each child directory of the '" + container.getName() + "' resource container.");
                task.getContainerName().set(container.getName());
                task.getResourcePath().set(container.getResourcePath());
                task.getArchiveDirectory().set(container.getArchiveDirectory());
                task.getRequiredFiles().set(container.getRequiredFiles());
                task.getAllowEmpty().set(container.getAllowEmpty());
                task.getExcludedChildren().set(project.provider(() -> extension.getBundles().stream()
                    .filter(bundle -> container.getName().equals(bundle.getContainer().get()))
                    .map(bundle -> normalizePath(bundle.getResourcePath().get()).split("/", 2)[0])
                    .distinct()
                    .sorted()
                    .toList()
                ));
                task.getContainerDirectories().from(mainResources.getSourceDirectories().getElements().map(elements ->
                    elements.stream()
                        .map(location -> new File(location.getAsFile(), normalizePath(container.getResourcePath().get())))
                        .toList()
                ));
                task.getBundledResourcesDirectory().convention(
                    project.getLayout().getBuildDirectory().dir("generated/zipBundler/containers/" + container.getName() + "/resources")
                );
                task.getMetadataFile().convention(
                    project.getLayout().getBuildDirectory().file("generated/zipBundler/containers/" + container.getName() + "/mappings.properties")
                );
            });

            mainResources.exclude(element -> belongsToPath(element, container.getResourcePath().get()));
            processResources.from(containerTask.flatMap(BundleResourceContainer::getBundledResourcesDirectory), spec ->
                spec.setDuplicatesStrategy(DuplicatesStrategy.FAIL)
            );
            metadataTask.configure(task -> {
                task.getContainerNames().add(container.getName());
                task.getMappingFragments().from(containerTask.flatMap(BundleResourceContainer::getMetadataFile));
            });
            initializeDirectories.configure(task -> task.getContainerDirectories().from(project.provider(() ->
                mainResources.getSrcDirs().stream()
                    .map(root -> new File(root, normalizePath(container.getResourcePath().get())))
                    .toList()
            )));
        });
    }

    private static boolean belongsToPath(FileTreeElement element, String configuredPath) {
        String resourcePath = normalizePath(configuredPath);
        String path = normalizePath(element.getPath());
        return path.equals(resourcePath) || path.startsWith(resourcePath + "/");
    }

    private static void validateBundle(ZipBundle bundle, String resolvedResourcePath, String resolvedArchivePath) {
        String resourcePath = normalizePath(resolvedResourcePath);
        String archivePath = normalizePath(resolvedArchivePath);
        validateRelativePath("resourcePath", resourcePath, bundle.getName());
        validateRelativePath("archivePath", archivePath, bundle.getName());
        if (!archivePath.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("archivePath for bundle '" + bundle.getName() + "' must end in .zip");
        }
        if (archivePath.equalsIgnoreCase(METADATA_PATH)) {
            throw new IllegalArgumentException("archivePath for bundle '" + bundle.getName() + "' conflicts with " + METADATA_PATH);
        }
    }

    private static String resolveResourcePath(ZipBundlerExtension extension, ZipBundle bundle) {
        String containerName = bundle.getContainer().get();
        validateContainerName(containerName);
        String relativeResourcePath = normalizePath(bundle.getResourcePath().get());
        validateRelativePath("resourcePath", relativeResourcePath, bundle.getName());
        if (ROOT_CONTAINER_NAME.equals(containerName)) {
            return relativeResourcePath;
        }

        ZipBundleContainer container = requireContainer(extension, bundle, containerName);
        String containerResourcePath = normalizePath(container.getResourcePath().get());
        validateRelativePath("resourcePath", containerResourcePath, containerName);
        return containerResourcePath + "/" + relativeResourcePath;
    }

    private static String resolveArchivePath(ZipBundlerExtension extension, ZipBundle bundle) {
        String containerName = bundle.getContainer().get();
        validateContainerName(containerName);
        String relativeArchivePath = normalizePath(bundle.getArchivePath().get());
        validateRelativePath("archivePath", relativeArchivePath, bundle.getName());
        if (ROOT_CONTAINER_NAME.equals(containerName)) {
            return relativeArchivePath;
        }

        ZipBundleContainer container = requireContainer(extension, bundle, containerName);
        String archiveDirectory = normalizePath(container.getArchiveDirectory().get());
        validateRelativePath("archiveDirectory", archiveDirectory, containerName);
        return archiveDirectory + "/" + relativeArchivePath;
    }

    private static ZipBundleContainer requireContainer(
        ZipBundlerExtension extension,
        ZipBundle bundle,
        String containerName
    ) {
        ZipBundleContainer container = extension.getContainers().findByName(containerName);
        if (container == null) {
            throw new IllegalArgumentException(
                "Explicit bundle '" + bundle.getName() + "' references undeclared container '" + containerName + "'"
            );
        }
        return container;
    }

    private static List<String> requiredFiles(ZipBundlerExtension extension, ZipBundle bundle) {
        LinkedHashSet<String> requiredFiles = new LinkedHashSet<>();
        ZipBundleContainer container = bundleContainer(extension, bundle);
        if (container != null) {
            requiredFiles.addAll(container.getRequiredFiles().get());
        }
        requiredFiles.addAll(bundle.getRequiredFiles().get());
        return List.copyOf(requiredFiles);
    }

    private static boolean allowEmpty(ZipBundlerExtension extension, ZipBundle bundle) {
        ZipBundleContainer container = bundleContainer(extension, bundle);
        return bundle.getAllowEmpty().get()
            && (container == null || container.getAllowEmpty().get());
    }

    private static ZipBundleContainer bundleContainer(ZipBundlerExtension extension, ZipBundle bundle) {
        String containerName = bundle.getContainer().get();
        if (ROOT_CONTAINER_NAME.equals(containerName)) {
            return null;
        }
        return requireContainer(extension, bundle, containerName);
    }

    static void validateRelativePath(String property, String path, String bundleName) {
        if (path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(property + " for bundle '" + bundleName + "' must be a non-empty relative path");
        }
        for (String segment : path.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(property + " for bundle '" + bundleName + "' contains an invalid path segment");
            }
        }
    }

    static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    static String containerDescriptorPath(String containerName) {
        return "META-INF/zip-bundler/containers/" + containerName + ".properties";
    }

    static void validateContainerName(String containerName) {
        if (!containerName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                "Container name '" + containerName + "' may only contain letters, digits, '.', '_' and '-'"
            );
        }
    }

    private static String fileName(String path) {
        String normalized = normalizePath(path);
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static String parentPath(String path) {
        String normalized = normalizePath(path);
        int separator = normalized.lastIndexOf('/');
        return separator < 0 ? "" : normalized.substring(0, separator);
    }

    private static String taskNamePart(String name) {
        StringBuilder result = new StringBuilder();
        boolean capitalize = true;
        for (char character : name.toCharArray()) {
            if (!Character.isLetterOrDigit(character)) {
                capitalize = true;
            } else if (capitalize) {
                result.append(Character.toUpperCase(character));
                capitalize = false;
            } else {
                result.append(character);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Bundle name must contain at least one letter or digit");
        }
        return result.toString();
    }
}
