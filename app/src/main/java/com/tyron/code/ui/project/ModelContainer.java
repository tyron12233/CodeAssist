package com.tyron.code.ui.project;

import androidx.annotation.NonNull;

import com.tyron.builder.model.SyncIssue;
import com.tyron.builder.model.level2.GlobalLibraryMap;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import org.gradle.tooling.model.BuildIdentifier;

import javax.annotation.Nullable;

public final class ModelContainer<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final BuildIdentifier rootBuildId;
    @NonNull private final Map<BuildIdentifier, Map<String, T>> modelMap;
    @NonNull private final Map<BuildIdentifier, Multimap<String, SyncIssue>> syncIssuesMap;
    @Nullable
    private final GlobalLibraryMap globalLibraryMap;

    public ModelContainer(
            @NonNull BuildIdentifier rootBuildId,
            @NonNull Map<BuildIdentifier, Map<String, T>> modelMap,
            @NonNull Map<BuildIdentifier, Multimap<String, SyncIssue>> syncIssuesMap,
            @Nullable GlobalLibraryMap globalLibraryMap) {
        this.rootBuildId = rootBuildId;
        this.modelMap = modelMap;
        this.syncIssuesMap = syncIssuesMap;
        this.globalLibraryMap = globalLibraryMap;
    }

    @NonNull
    public T getOnlyModel() {
        if (modelMap.keySet().size() != 1) {
            throw new RuntimeException("Can't call getOnlyModel with included builds");
        }
        return Iterables.getOnlyElement(getRootBuildModelMap().values());
    }

    /** Returns the only model map. This is only valid if there is no included builds. */
    @NonNull
    public Map<String, T> getOnlyModelMap() {
        if (modelMap.keySet().size() > 1) {
            throw new RuntimeException("Can't call getOnlyModelMap with included builds");
        }

        return getRootBuildModelMap();
    }

    @NonNull
    public BuildIdentifier getRootBuildId() {
        return rootBuildId;
    }

    @NonNull
    public Map<String, T> getRootBuildModelMap() {
        return modelMap.get(rootBuildId);
    }

    @NonNull
    public Map<BuildIdentifier, Map<String, T>> getModelMaps() {
        return modelMap;
    }

    @NonNull
    public Collection<SyncIssue> getOnlyModelSyncIssues() {
        if (syncIssuesMap.keySet().size() != 1) {
            throw new RuntimeException("Can't call getOnlyModelSyncIssues with included builds");
        }
        return getRootBuildSyncIssuesMap().values();
    }

    @NonNull
    public Multimap<String, SyncIssue> getOnlyModelSyncIssuesMap() {
        if (syncIssuesMap.keySet().size() > 1) {
            throw new RuntimeException("Can't call getOnlyModelSyncIssuesMap with included builds");
        }
        return getRootBuildSyncIssuesMap();
    }

    @NonNull
    private Multimap<String, SyncIssue> getRootBuildSyncIssuesMap() {
        return syncIssuesMap.get(rootBuildId);
    }

    @NonNull
    public Map<BuildIdentifier, Multimap<String, SyncIssue>> getSyncIssuesMap() {
        return syncIssuesMap;
    }

    @NonNull
    public GlobalLibraryMap getGlobalLibraryMap() {
        return Preconditions.checkNotNull(globalLibraryMap);
    }
}