package me.javierflores.zipbundler.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Comparator;

/** Explicit filesystem cleanup operations for bundle consumers. */
public final class ZipBundleFiles {
    private ZipBundleFiles() {
    }

    /**
     * Deletes every entry below {@code directory} while preserving the directory itself.
     *
     * <p>This method does not follow symbolic links. A symbolic-link root is rejected;
     * nested symbolic links are deleted without touching their targets. Missing directories
     * are accepted. This operation also removes unrelated pre-existing contents.</p>
     */
    public static void clearDirectory(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (root.getParent() == null) {
            throw new IOException("Refusing to clear filesystem root: " + root);
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Refusing to clear a symbolic-link directory: " + root);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotDirectoryException(root.toString());
        }

        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) {
                    Files.delete(path);
                }
            }
        }
    }
}
