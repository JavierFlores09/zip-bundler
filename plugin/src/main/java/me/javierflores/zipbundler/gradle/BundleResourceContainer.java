package me.javierflores.zipbundler.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@CacheableTask
public abstract class BundleResourceContainer extends DefaultTask {
    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getContainerDirectories();

    @Input
    public abstract Property<String> getResourcePath();

    @Input
    public abstract Property<String> getContainerName();

    @Input
    public abstract Property<String> getArchiveDirectory();

    /** Top-level container children handled by explicit bundle declarations. */
    @Input
    public abstract ListProperty<String> getExcludedChildren();

    @Input
    public abstract ListProperty<String> getRequiredFiles();

    @Input
    public abstract Property<Boolean> getAllowEmpty();

    @OutputDirectory
    public abstract DirectoryProperty getBundledResourcesDirectory();

    @OutputFile
    public abstract RegularFileProperty getMetadataFile();

    @TaskAction
    public void bundleChildren() throws IOException {
        String resourcePath = ZipBundlerPlugin.normalizePath(getResourcePath().get());
        String archiveDirectory = ZipBundlerPlugin.normalizePath(getArchiveDirectory().get());
        ZipBundlerPlugin.validateRelativePath("resourcePath", resourcePath, getName());
        ZipBundlerPlugin.validateRelativePath("archiveDirectory", archiveDirectory, getName());

        Path outputRoot = getBundledResourcesDirectory().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(outputRoot));
        Files.createDirectories(outputRoot);

        Map<String, List<Path>> bundles = discoverBundles();
        Map<String, BundleArchiveSupport.BundleMetadata> mappings = new TreeMap<>();
        for (Map.Entry<String, List<Path>> bundle : bundles.entrySet()) {
            String childName = bundle.getKey();
            String sourcePath = resourcePath + "/" + childName;
            String archivePath = archiveDirectory + "/" + childName + ".zip";
            Path outputZip = outputRoot.resolve(archivePath.replace('/', outputRoot.getFileSystem().getSeparator().charAt(0)));
            var archive = BundleArchiveSupport.writeZip(
                bundle.getValue(),
                outputZip,
                sourcePath,
                getRequiredFiles().get(),
                getAllowEmpty().get()
            );
            mappings.put(childName, new BundleArchiveSupport.BundleMetadata(archivePath, archive.sha1(), archive.size()));
        }

        BundleArchiveSupport.writeFragment(
            getMetadataFile().get().getAsFile().toPath(),
            getContainerName().get(),
            mappings
        );
    }

    private Map<String, List<Path>> discoverBundles() throws IOException {
        Map<String, List<Path>> bundles = new TreeMap<>();
        for (java.io.File containerFile : getContainerDirectories().getFiles()) {
            Path container = containerFile.toPath();
            if (!Files.exists(container)) {
                continue;
            }
            if (!Files.isDirectory(container)) {
                throw new IllegalArgumentException("Resource bundle container is not a directory: " + container);
            }
            try (var children = Files.list(container)) {
                for (Path child : children.sorted().toList()) {
                    if (!Files.isDirectory(child)) {
                        throw new IllegalArgumentException(
                            "Resource bundle container '" + getResourcePath().get() + "' may only contain directories; found " + child.getFileName()
                        );
                    }
                    if (getExcludedChildren().get().contains(child.getFileName().toString())) {
                        continue;
                    }
                    bundles.computeIfAbsent(child.getFileName().toString(), ignored -> new ArrayList<>()).add(child);
                }
            }
        }
        return bundles;
    }

}
