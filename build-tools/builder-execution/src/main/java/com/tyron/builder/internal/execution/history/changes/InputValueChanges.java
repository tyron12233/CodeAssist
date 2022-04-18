package com.tyron.builder.internal.execution.history.changes;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.Describable;
import com.tyron.builder.internal.execution.history.DescriptiveChange;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.impl.ImplementationSnapshot;

import java.util.Map;

class InputValueChanges implements ChangeContainer {
    private final Describable executable;
    private final ImmutableMap<String, String> changed;

    public InputValueChanges(ImmutableSortedMap<String, ValueSnapshot> previous, ImmutableSortedMap<String, ValueSnapshot> current, Describable executable) {
        ImmutableMap.Builder<String, String> changedBuilder = ImmutableMap.builder();
        for (Map.Entry<String, ValueSnapshot> entry : current.entrySet()) {
            String propertyName = entry.getKey();
            ValueSnapshot currentSnapshot = entry.getValue();
            ValueSnapshot previousSnapshot = previous.get(propertyName);
            if (previousSnapshot != null) {
                if (!currentSnapshot.equals(previousSnapshot)) {
                    changedBuilder.put(
                            propertyName,
                            currentSnapshot instanceof ImplementationSnapshot ? "Implementation" : "Value");
                }
            }
        }
        this.changed = changedBuilder.build();
        this.executable = executable;
    }

    @Override
    public boolean accept(ChangeVisitor visitor) {
        for (Map.Entry<String, String> entry : changed.entrySet()) {
            String propertyName = entry.getKey();
            String changeType = entry.getValue();
            if (!visitor.visitChange(new DescriptiveChange("%s of input property '%s' has changed for %s",
                    changeType, propertyName, executable.getDisplayName()))) {
                return false;
            }
        }
        return true;
    }
}