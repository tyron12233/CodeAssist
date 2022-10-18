package org.gradle.plugins.ide.idea.model;

import com.google.common.base.Objects;

/**
 * Represents a jar directory element of an idea module library.
 */
public class JarDirectory {

    private Path path;
    private boolean recursive;

    public JarDirectory(Path path, boolean recursive) {
        this.path = path;
        this.recursive = recursive;
    }

    /**
     * The path of the jar directory
     */
    public Path getPath() {
        return path;
    }

    /**
     * The value for the recursive attribute of the jar directory element.
     */
    public void setPath(Path path) {
        this.path = path;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public void setRecursive(boolean recursive) {
        this.recursive = recursive;
    }

    @Override
    public String toString() {
        return "JarDirectory{" + "path=" + path + ", recursive=" + recursive + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!getClass().equals(o.getClass())) {
            return false;
        }
        JarDirectory that = (JarDirectory) o;
        return recursive == that.recursive
            && Objects.equal(path, that.path);
    }

    @Override
    public int hashCode() {
        int result;
        result = path.hashCode();
        result = 31 * result + (recursive ? 1 : 0);
        return result;
    }
}