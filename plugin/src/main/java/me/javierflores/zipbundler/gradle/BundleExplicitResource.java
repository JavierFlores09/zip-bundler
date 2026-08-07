package me.javierflores.zipbundler.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@CacheableTask
public abstract class BundleExplicitResource extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceDirectories();

    @Input
    public abstract Property<String> getContainerName();

    @Input
    public abstract Property<String> getBundleName();

    @Input
    public abstract Property<String> getResourcePath();

    @Input
    public abstract Property<String> getArchivePath();

    @Input
    public abstract ListProperty<String> getRequiredFiles();

    @Input
    public abstract Property<Boolean> getAllowEmpty();

    @OutputFile
    public abstract RegularFileProperty getArchiveFile();

    @OutputFile
    public abstract RegularFileProperty getMetadataFile();

    @TaskAction
    public void bundle() throws IOException {
        List<Path> existingDirectories = getSourceDirectories().getFiles().stream()
            .map(java.io.File::toPath)
            .filter(Files::exists)
            .toList();
        if (existingDirectories.isEmpty()) {
            throw new IllegalArgumentException(
                "No resource directory exists for explicit bundle '" + getBundleName().get() + "' at " + getResourcePath().get()
            );
        }
        for (Path directory : existingDirectories) {
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("Explicit bundle source is not a directory: " + directory);
            }
        }

        String archivePath = ZipBundlerPlugin.normalizePath(getArchivePath().get());
        var archive = BundleArchiveSupport.writeZip(
            existingDirectories,
            getArchiveFile().get().getAsFile().toPath(),
            getResourcePath().get(),
            getRequiredFiles().get(),
            getAllowEmpty().get()
        );
        BundleArchiveSupport.writeFragment(
            getMetadataFile().get().getAsFile().toPath(),
            getContainerName().get(),
            Map.of(getBundleName().get(), new BundleArchiveSupport.BundleMetadata(archivePath, archive.sha1(), archive.size()))
        );
    }
}
