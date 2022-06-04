package com.tyron.builder.api.java.archives.internal;

import com.tyron.builder.api.java.archives.ManifestMergeDetails;

public class DefaultManifestMergeDetails implements ManifestMergeDetails {
    private String section;
    private String key;
    private String baseValue;
    private String mergeValue;
    private String value;
    private boolean excluded;

    public DefaultManifestMergeDetails(String section, String key, String baseValue, String mergeValue, String value) {
        this.section = section;
        this.key = key;
        this.baseValue = baseValue;
        this.mergeValue = mergeValue;
        this.value = value;
    }

    @Override
    public String getSection() {
        return section;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String getBaseValue() {
        return baseValue;
    }

    @Override
    public String getMergeValue() {
        return mergeValue;
    }

    @Override
    public String getValue() {
        return value;
    }

    public boolean isExcluded() {
        return excluded;
    }

    @Override
    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public void exclude() {
        excluded = true;
    }
}
