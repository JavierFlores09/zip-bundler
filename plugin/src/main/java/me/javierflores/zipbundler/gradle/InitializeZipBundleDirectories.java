package me.javierflores.zipbundler.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.file.Files;

/** Creates configured bundle-container directories in the source tree. */
@DisableCachingByDefault(because = "Creates configured source directories outside the build directory")
public abstract class InitializeZipBundleDirectories extends DefaultTask {
    public InitializeZipBundleDirectories() {
        doNotTrackState("Creates source directories outside the build directory");
    }

    @Internal
    public abstract ConfigurableFileCollection getContainerDirectories();

    @TaskAction
    public void createDirectories() throws IOException {
        for (java.io.File directory : getContainerDirectories().getFiles()) {
            Files.createDirectories(directory.toPath());
        }
    }
}
