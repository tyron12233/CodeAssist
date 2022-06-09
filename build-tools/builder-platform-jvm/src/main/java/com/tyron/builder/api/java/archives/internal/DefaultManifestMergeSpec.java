package com.tyron.builder.api.java.archives.internal;

import com.google.common.collect.Sets;
import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.java.archives.Attributes;
import com.tyron.builder.api.java.archives.Manifest;
import com.tyron.builder.api.java.archives.ManifestMergeDetails;
import com.tyron.builder.api.java.archives.ManifestMergeSpec;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.util.internal.ConfigureUtil;
import com.tyron.builder.util.internal.GUtil;
import com.tyron.builder.util.internal.WrapUtil;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class DefaultManifestMergeSpec implements ManifestMergeSpec {
    List<Object> mergePaths = new ArrayList<Object>();
    private final List<Action<? super ManifestMergeDetails>> actions = new ArrayList<Action<? super ManifestMergeDetails>>();
    private String contentCharset = DefaultManifest.DEFAULT_CONTENT_CHARSET;

    @Override
    public String getContentCharset() {
        return this.contentCharset;
    }

    @Override
    public void setContentCharset(String contentCharset) {
        if (contentCharset == null) {
            throw new InvalidUserDataException("contentCharset must not be null");
        }
        if (!Charset.isSupported(contentCharset)) {
            throw new InvalidUserDataException(String.format("Charset for contentCharset '%s' is not supported by your JVM", contentCharset));
        }
        this.contentCharset = contentCharset;
    }

    @Override
    public ManifestMergeSpec from(Object... mergePaths) {
        GUtil.flatten(mergePaths, this.mergePaths);
        return this;
    }

    @Override
    public ManifestMergeSpec eachEntry(Action<? super ManifestMergeDetails> mergeAction) {
        actions.add(mergeAction);
        return this;
    }

    @Override
    public ManifestMergeSpec eachEntry(Closure<?> mergeAction) {
        return eachEntry(ConfigureUtil.configureUsing(mergeAction));
    }

    public DefaultManifest merge(Manifest baseManifest, PathToFileResolver fileResolver) {
        String baseContentCharset = baseManifest instanceof ManifestInternal ? ((ManifestInternal) baseManifest).getContentCharset() : DefaultManifest.DEFAULT_CONTENT_CHARSET;
        DefaultManifest mergedManifest = new DefaultManifest(fileResolver, baseContentCharset);
        mergedManifest.getAttributes().putAll(baseManifest.getAttributes());
        mergedManifest.getSections().putAll(baseManifest.getSections());
        for (Object mergePath : mergePaths) {
            DefaultManifest manifestToMerge = createManifest(mergePath, fileResolver, contentCharset);
            mergedManifest = mergeManifest(mergedManifest, manifestToMerge, fileResolver);
        }
        return mergedManifest;
    }

    private DefaultManifest mergeManifest(DefaultManifest baseManifest, DefaultManifest toMergeManifest, PathToFileResolver fileResolver) {
        DefaultManifest mergedManifest = new DefaultManifest(fileResolver);
        mergeSection(null, mergedManifest, baseManifest.getAttributes(), toMergeManifest.getAttributes());
        Set<String> allSections = Sets.union(baseManifest.getSections().keySet(), toMergeManifest.getSections().keySet());
        for (String section : allSections) {
            mergeSection(section, mergedManifest, Objects.requireNonNull(
                    GUtil.getOrDefault(baseManifest.getSections().get(section),
                            DefaultAttributes::new)),
                    GUtil.getOrDefault(toMergeManifest.getSections().get(section), DefaultAttributes::new));
        }
        return mergedManifest;
    }

    private void mergeSection(String section, DefaultManifest mergedManifest, Attributes baseAttributes, Attributes mergeAttributes) {
        Map<String, Object> mergeOnlyAttributes = new LinkedHashMap<String, Object>(mergeAttributes);
        Set<DefaultManifestMergeDetails> mergeDetailsSet = new LinkedHashSet<DefaultManifestMergeDetails>();

        for (Map.Entry<String, Object> baseEntry : baseAttributes.entrySet()) {
            Object mergeValue = mergeAttributes.get(baseEntry.getKey());
            mergeDetailsSet.add(getMergeDetails(section, baseEntry.getKey(), baseEntry.getValue(), mergeValue));
            mergeOnlyAttributes.remove(baseEntry.getKey());
        }
        for (Map.Entry<String, Object> mergeEntry : mergeOnlyAttributes.entrySet()) {
            mergeDetailsSet.add(getMergeDetails(section, mergeEntry.getKey(), null, mergeEntry.getValue()));
        }

        for (DefaultManifestMergeDetails mergeDetails : mergeDetailsSet) {
            for (Action<? super ManifestMergeDetails> action : actions) {
                action.execute(mergeDetails);
            }
            addMergeDetailToManifest(section, mergedManifest, mergeDetails);
        }
    }

    private DefaultManifestMergeDetails getMergeDetails(String section, String key, Object baseValue, Object mergeValue) {
        String baseValueString = resolveValueToString(baseValue);
        String mergeValueString = resolveValueToString(mergeValue);
        String value = mergeValueString == null ? baseValueString : mergeValueString;
        return new DefaultManifestMergeDetails(section, key, baseValueString, mergeValueString, value);
    }

    private static String resolveValueToString(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof Provider) {
            Object providedValue = ((Provider<?>) value).getOrNull();
            return resolveValueToString(providedValue);
        } else {
            return value.toString();
        }
    }

    private void addMergeDetailToManifest(String section, DefaultManifest mergedManifest, DefaultManifestMergeDetails mergeDetails) {
        if (!mergeDetails.isExcluded()) {
            if (section == null) {
                mergedManifest.attributes(WrapUtil.toMap(mergeDetails.getKey(), mergeDetails.getValue()));
            } else {
                mergedManifest.attributes(WrapUtil.toMap(mergeDetails.getKey(), mergeDetails.getValue()), section);
            }
        }
    }

    private DefaultManifest createManifest(Object mergePath, PathToFileResolver fileResolver, String contentCharset) {
        if (mergePath instanceof DefaultManifest) {
            return ((DefaultManifest) mergePath).getEffectiveManifest();
        }
        return new DefaultManifest(mergePath, fileResolver, contentCharset);
    }

    public List<Object> getMergePaths() {
        return mergePaths;
    }
}
