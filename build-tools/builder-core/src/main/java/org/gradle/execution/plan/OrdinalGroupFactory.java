package org.gradle.execution.plan;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

import java.util.ArrayList;
import java.util.List;

/**
 * Preserves identity of {@see OrdinalGroup} instances so there's a 1-to-1 mapping of ordinals to groups allowing groups
 * to be freely compared by identity.
 */
@ServiceScope(Scopes.Build.class)
public class OrdinalGroupFactory {

    private final List<OrdinalGroup> groups = new ArrayList<>();

    public final OrdinalGroup group(int ordinal) {
        growTo(ordinal);
        return groups.get(ordinal);
    }

    public List<OrdinalGroup> getAllGroups() {
        return groups;
    }

    public void reset() {
        groups.clear();
    }

    private void growTo(int ordinal) {
        for (int i = groups.size(); i <= ordinal; ++i) {
            groups.add(new OrdinalGroup(i));
        }
    }
}