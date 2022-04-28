package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resource qualifier for Platform version. */
public final class VersionQualifier extends ResourceQualifier {
    /** Default version. This means the property is not set. */
    public static final int DEFAULT_VERSION = -1;

    private static final Pattern sVersionPattern = Pattern.compile("^v(\\d+)$");

    private int mVersion = DEFAULT_VERSION;

    public static final String NAME = "Platform Version";

    /**
     * Creates and returns a qualifier from the given folder segment. If the segment is incorrect,
     * {@code null} is returned.
     *
     * @param segment the folder segment from which to create a qualifier
     * @return a new VersionQualifier object or {@code null}
     */
    public static VersionQualifier getQualifier(@NotNull String segment) {
        Matcher m = sVersionPattern.matcher(segment);
        if (m.matches()) {
            String v = m.group(1);

            try {
                return new VersionQualifier(Integer.parseInt(v));
            } catch (NumberFormatException e) {
                // Not a valid version qualifier segment - return null.
            }
        }

        return null;
    }

    /**
     * Returns the folder name segment for the given version value. This is equivalent to calling
     * {@code new VersionQualifier(version).toString()}.
     *
     * @param version the value of the qualifier, as returned by {@link #getVersion()}.
     */
    public static String getFolderSegment(int version) {
        return version == DEFAULT_VERSION ? "" : 'v' + Integer.toString(version);
    }

    public VersionQualifier(int apiLevel) {
        mVersion = apiLevel;
    }

    public VersionQualifier() {
        //pass
    }

    public int getVersion() {
        return mVersion;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getShortName() {
        return "Version";
    }

    @Override
    public int since() {
        return 1;
    }

    @Override
    public boolean isValid() {
        return mVersion != DEFAULT_VERSION;
    }

    @Override
    public boolean hasFakeValue() {
        return false;
    }

    @Override
    public boolean checkAndSet(@NotNull String value, @NotNull FolderConfiguration config) {
        VersionQualifier qualifier = getQualifier(value);
        if (qualifier != null) {
            config.setVersionQualifier(qualifier);
            return true;
        }

        return false;
    }

    @Override
    public boolean equals(@Nullable Object qualifier) {
        return qualifier instanceof VersionQualifier
                && mVersion == ((VersionQualifier) qualifier).mVersion;

    }

    @Override
    public boolean isMatchFor(@NotNull ResourceQualifier qualifier) {
        if (qualifier instanceof VersionQualifier) {
            // It is considered a match if our API level is equal or lower to the given qualifier,
            // or the given qualifier doesn't specify an API Level.
            return mVersion <= ((VersionQualifier) qualifier).mVersion
                    || ((VersionQualifier) qualifier).mVersion == DEFAULT_VERSION;
        }

        return false;
    }

    @Override
    public boolean isBetterMatchThan(
            @Nullable ResourceQualifier compareTo, @NotNull ResourceQualifier reference) {
        if (compareTo == null) {
            return true;
        }

        VersionQualifier compareQ = (VersionQualifier) compareTo;
        VersionQualifier referenceQ = (VersionQualifier) reference;

        if (compareQ.mVersion == referenceQ.mVersion) {
            // what we have is already the best possible match (exact match)
            return false;
        } else if (mVersion == referenceQ.mVersion) {
            // got new exact value, this is the best!
            return true;
        } else {
            // In all case we're going to prefer the higher version (since they have been filtered
            // to not be too high.)
            return mVersion > compareQ.mVersion;
        }
    }

    @Override
    public int hashCode() {
        return mVersion;
    }

    /**
     * Returns the string used to represent this qualifier in the folder name.
     */
    @Override
    public String getFolderSegment() {
        return getFolderSegment(mVersion);
    }

    @Override
    public String getShortDisplayValue() {
        return mVersion == DEFAULT_VERSION ? "" : "API " + mVersion;
    }

    @Override
    public String getLongDisplayValue() {
        return mVersion == DEFAULT_VERSION ? "" : "API Level " + mVersion;
    }
}
