package me.javierflores.zipbundler.runtime;

import java.util.Objects;

/** Immutable metadata for an embedded ZIP bundle. */
public record BundleInfo(String containerName, String name, String path, String sha1, long size) {
    public BundleInfo {
        Objects.requireNonNull(containerName, "containerName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha1, "sha1");
        if (containerName.isBlank()) {
            throw new IllegalArgumentException("containerName must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (!sha1.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("sha1 must be a 40-character lowercase hexadecimal value");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }
}
