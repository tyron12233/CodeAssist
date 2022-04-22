package com.tyron.builder.internal.execution.history.changes;

import com.google.common.collect.ImmutableBiMap;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;

public class DefaultIncrementalInputProperties implements IncrementalInputProperties {
    private final ImmutableBiMap<String, Object> incrementalInputProperties;

    public DefaultIncrementalInputProperties(ImmutableBiMap<String, Object> incrementalInputProperties) {
        this.incrementalInputProperties = incrementalInputProperties;
    }

    @Override
    public String getPropertyNameFor(Object propertyValue) {
        String propertyName = incrementalInputProperties.inverse().get(propertyValue);
        if (propertyName == null) {
            throw new InvalidUserDataException("Cannot query incremental changes: No property found for value " + propertyValue + ". Incremental properties: " + Joiner.on(", ").join(incrementalInputProperties.keySet()) + ".");
        }
        return propertyName;
    }

    @Override
    public InputFileChanges nonIncrementalChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current) {
        return new DefaultInputFileChanges(
                Maps.filterKeys(previous, propertyName -> !incrementalInputProperties.containsKey(propertyName)),
                Maps.filterKeys(current, propertyName -> !incrementalInputProperties.containsKey(propertyName))
        );

    }

    @Override
    public InputFileChanges incrementalChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current) {
        return new DefaultInputFileChanges(
                ImmutableSortedMap.copyOfSorted(Maps.filterKeys(previous,
                        incrementalInputProperties::containsKey)),
                ImmutableSortedMap.copyOfSorted(Maps.filterKeys(current,
                        incrementalInputProperties::containsKey))
        );
    }
}