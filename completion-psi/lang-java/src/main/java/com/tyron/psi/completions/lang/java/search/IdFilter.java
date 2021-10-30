package com.tyron.psi.completions.lang.java.search;

import com.tyron.psi.roots.ContentIterator;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValue;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValueProvider;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValuesManager;

import java.util.BitSet;

public abstract class IdFilter {
    private static final Logger LOG = Logger.getInstance(IdFilter.class);
    private static final Key<CachedValue<IdFilter>> INSIDE_PROJECT = Key.create("INSIDE_PROJECT");
    private static final Key<CachedValue<IdFilter>> OUTSIDE_PROJECT = Key.create("OUTSIDE_PROJECT");

    public enum FilterScopeType {
        OTHER {
            @Override
            public @NonNls @NotNull String getId() {
                throw new UnsupportedOperationException();
            }
        },
        PROJECT {
            @Override
            public @NonNls @NotNull String getId() {
                return "false";
            }
        },
        PROJECT_AND_LIBRARIES {
            @Override
            public @NonNls @NotNull String getId() {
                return "true";
            }
        };

        @NonNls
        @NotNull
        public abstract String getId();
    }

    @NotNull
    public static IdFilter getProjectIdFilter(@NotNull Project project, final boolean includeNonProjectItems) {
        Key<CachedValue<IdFilter>> key = includeNonProjectItems ? OUTSIDE_PROJECT : INSIDE_PROJECT;
        CachedValueProvider<IdFilter> provider = () -> CachedValueProvider.Result.create(buildProjectIdFilter(project, includeNonProjectItems),
              VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS);
        return CachedValuesManager.getManager(project).getCachedValue(project, key, provider, false);
    }

    @NotNull
    private static IdFilter buildProjectIdFilter(@NotNull Project project, boolean includeNonProjectItems) {
        long started = System.currentTimeMillis();
        final BitSet idSet = new BitSet();

        ContentIterator iterator = fileOrDir -> {
            idSet.set(((VirtualFileWithId)fileOrDir).getId());
            ProgressManager.checkCanceled();
            return true;
        };

//        if (includeNonProjectItems) {
//            FileBasedIndex.getInstance().iterateIndexableFiles(iterator, project, ProgressIndicatorProvider.getGlobalProgressIndicator());
//        }
//        else {
//            ProjectRootManager.getInstance(project).getFileIndex().iterateContent(iterator);
//        }

        if (LOG.isDebugEnabled()) {
            long elapsed = System.currentTimeMillis() - started;
            LOG.debug("Done filter (includeNonProjectItems=" + includeNonProjectItems+") "+
                    "in " + elapsed + "ms. Total files in set: " + idSet.cardinality());
        }
        return new IdFilter() {
            @Override
            public boolean containsFileId(int id) {
                return id >= 0 && idSet.get(id);
            }

            @NotNull
            @Override
            public FilterScopeType getFilteringScopeType() {
                return includeNonProjectItems ? FilterScopeType.PROJECT_AND_LIBRARIES : FilterScopeType.PROJECT;
            }
        };
    }

    public abstract boolean containsFileId(int id);

    @NotNull
    public FilterScopeType getFilteringScopeType() {
        return FilterScopeType.OTHER;
    }
}