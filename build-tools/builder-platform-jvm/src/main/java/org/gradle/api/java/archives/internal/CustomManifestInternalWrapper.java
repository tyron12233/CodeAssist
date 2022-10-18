package org.gradle.api.java.archives.internal;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.java.archives.Attributes;
import org.gradle.api.java.archives.Manifest;
import org.gradle.api.java.archives.ManifestException;
import org.gradle.api.java.archives.ManifestMergeSpec;

import java.io.OutputStream;
import java.util.Map;

public class CustomManifestInternalWrapper implements ManifestInternal {
    private final Manifest delegate;
    private String contentCharset = DefaultManifest.DEFAULT_CONTENT_CHARSET;

    public CustomManifestInternalWrapper(Manifest delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getContentCharset() {
        return contentCharset;
    }

    @Override
    public void setContentCharset(String contentCharset) {
        this.contentCharset = contentCharset;
    }

    @Override
    public Manifest writeTo(OutputStream outputStream) {
        DefaultManifest.writeTo(this, outputStream, contentCharset);
        return this;
    }

    @Override
    public Attributes getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Map<String, Attributes> getSections() {
        return delegate.getSections();
    }

    @Override
    public Manifest attributes(Map<String, ?> attributes) throws ManifestException {
        return delegate.attributes(attributes);
    }

    @Override
    public Manifest attributes(Map<String, ?> attributes, String sectionName) throws ManifestException {
        return delegate.attributes(attributes, sectionName);
    }

    @Override
    public Manifest getEffectiveManifest() {
        return delegate.getEffectiveManifest();
    }

    @Override
    public Manifest writeTo(Object path) {
        return delegate.writeTo(path);
    }

    @Override
    public Manifest from(Object... mergePath) {
        return delegate.from(mergePath);
    }

    @Override
    public Manifest from(Object mergePath, Closure<?> closure) {
        return delegate.from(mergePath, closure);
    }

    @Override
    public Manifest from(Object mergePath, Action<ManifestMergeSpec> action) {
        return delegate.from(mergePath, action);
    }
}
