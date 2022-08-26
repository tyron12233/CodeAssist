package org.gradle.plugins.ide.idea.model;

import com.google.common.base.Objects;

/**
 * Represents a path in a format as used often in ipr and iml files.
 */
public class Path {

    private final String url;
    private final String relPath;
    private final String canonicalUrl;

    public Path(String url) {
        this(url, url, null);
    }

    public Path(String url, String canonicalUrl, String relPath) {
        this.relPath = relPath;
        this.url = url;
        this.canonicalUrl = canonicalUrl;
    }

    /**
     * The url of the path. Must not be null.
     */
    public String getUrl() {
        return url;
    }

    /**
     * The relative path of the path. Must not be null.
     */
    public String getRelPath() {
        return relPath;
    }

    /**
     * Canonical url.
     */
    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    @Override
    public String toString() {
        return "Path{" + "url='" + url + "\'" + "}";
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Path)) {
            return false;
        }
        Path path = (Path) o;
        return Objects.equal(canonicalUrl, path.canonicalUrl);
    }

    @Override
    public int hashCode() {
        return canonicalUrl.hashCode();
    }
}