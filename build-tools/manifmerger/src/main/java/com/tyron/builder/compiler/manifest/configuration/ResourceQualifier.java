package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * Base class for resource qualifiers.
 *
 * <p>The resource qualifier classes are designed as immutable.
 */
public abstract class ResourceQualifier implements Comparable<ResourceQualifier>, Serializable {

    /**
     * Returns the human readable name of the qualifier.
     */
    public abstract String getName();

    /**
     * Returns a shorter human readable name for the qualifier.
     *
     * @see #getName()
     */
    public abstract String getShortName();

    /**
     * Returns the API level when this qualifier was added to Android.
     */
    public abstract int since();

    /**
     * Whether this qualifier is deprecated.
     */
    public boolean deprecated() {
        return false;
    }

    /**
     * Returns whether the qualifier has a valid filter value.
     */
    public abstract boolean isValid();

    /**
     * Returns whether the qualifier has a fake value.
     *
     * Fake values are used internally and should not be used as real qualifier value.
     */
    public abstract boolean hasFakeValue();

    /**
     * Check if the value is valid for this qualifier, and if so sets the value into a Folder
     * Configuration.
     *
     * @param value  The value to check and set. Must not be null.
     * @param config The folder configuration to receive the value. Must not be null.
     * @return true if the value was valid and was set.
     */
    public abstract boolean checkAndSet(String value, FolderConfiguration config);

    /**
     * Returns a string formatted to be used in a folder name.
     */
    public abstract String getFolderSegment();

    /**
     * Returns the qualifier that can be used in {@link #isBetterMatchThan(ResourceQualifier,
     * ResourceQualifier)} when no qualifier is present in the config.
     *
     * null has a special meaning when trying to match the best config (it's the worst qualifier,
     * unless not other alternative matches). If a qualifier type has a different definition of best
     * in regards to the null qualifier, this method should be subclassed and a special value should
     * be returned from here, which can then be used to call {@link #isBetterMatchThan
     * (ResourceQualifier, ResourceQualifier)} which can implement the custom logic.
     */
    public ResourceQualifier getNullQualifier() {
        return null;
    }

    /**
     * Returns whether the given qualifier is a match for the receiver.
     *
     * The default implementation returns the result of {@link #equals(Object)}.
     *
     * Children class that re-implements this must implement {@link #isBetterMatchThan
     * (ResourceQualifier, ResourceQualifier)} too.
     *
     * @param qualifier the reference qualifier
     * @return true if the receiver is a match.
     */
    public boolean isMatchFor(ResourceQualifier qualifier) {
        return equals(qualifier);
    }

    /**
     * Returns true if the receiver (this) is a better match for the given {@code reference} than
     * the given {@code compareTo} comparable.
     *
     * @param compareTo The {@link ResourceQualifier} to compare to.
     * @param reference The reference qualifier value for which the match is (from phone's
     *                  folderConfig).
     */
    public boolean isBetterMatchThan(@Nullable ResourceQualifier compareTo,
                                     @NotNull ResourceQualifier reference) {
        // the default is to always return false. This gives less overhead than always returning
        // true, as it would only compare same values anyway.
        return compareTo == null;
    }

    @Override
    public String toString() {
        return getFolderSegment();
    }

    /**
     * Returns a string formatted for display purpose.
     */
    public abstract String getShortDisplayValue();

    /**
     * Returns a string formatted for display purpose.
     */
    public abstract String getLongDisplayValue();

    /**
     * Returns {@code true} if both objects are equal.
     *
     * This is declared as abstract to force children classes to implement it.
     */
    @Override
    public abstract boolean equals(Object object);

    /**
     * Returns a hash code value for the object.
     *
     * This is declared as abstract to force children classes to implement it.
     */
    @Override
    public abstract int hashCode();

    @Override
    public final int compareTo(@NotNull ResourceQualifier o) {
        return toString().compareTo(o.toString());
    }

    public static boolean isValid(@Nullable ResourceQualifier qualifier) {
        return qualifier != null && qualifier.isValid();
    }
}