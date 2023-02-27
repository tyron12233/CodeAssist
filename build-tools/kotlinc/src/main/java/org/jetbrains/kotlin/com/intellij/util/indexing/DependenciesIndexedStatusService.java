package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Service is used by platform to calculate which values provided by any of {@link AdditionalLibraryRootsProvider},
 * {@link IndexableSetContributor} or {@link com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy} were changed
 * to rescan only them on rootsChanged event with
 * {@link com.intellij.openapi.project.RootsChangeRescanningInfo#RESCAN_DEPENDENCIES_IF_NEEDED}
 * <p>
 * Note that non-null {@link SyntheticLibrary#getExcludeFileCondition()} is considered always changed, and then
 * {@link SyntheticLibrary} is rescanned incrementally only if its {@link AdditionalLibraryRootsProvider}
 * returns only one library. {@link SyntheticLibrary} with null {@link SyntheticLibrary#getExcludeFileCondition()}
 * and non-null comparisonId is always rescanned incrementally, matched by comparisonId,
 * and having constant exclusion condition {@link SyntheticLibrary.ExcludeFileCondition}.
 */
@ApiStatus.Internal
@ApiStatus.Experimental
public class DependenciesIndexedStatusService {
    public interface StatusMark {
        @Nullable
        static StatusMark mergeStatus(@Nullable StatusMark one, @Nullable StatusMark another) {
            if (one == null) return another;
            if (another == null) return one;
            if (((MyStatus)one).version > ((MyStatus)another).version) return one;
            return another;
        }
    }
}