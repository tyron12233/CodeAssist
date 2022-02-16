package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource Qualifier for Screen Pixel Density.
 */
public final class ScreenWidthQualifier extends ResourceQualifier {
    /** Default screen size value. This means the property is not set */
    static final int DEFAULT_SIZE = -1;

    private static final Pattern sParsePattern = Pattern.compile("^w(\\d+)dp$"); //$NON-NLS-1$
    private static final String sPrintPattern = "w%1$ddp"; //$NON-NLS-1$

    public static final String NAME = "Screen Width";

    private int mValue = DEFAULT_SIZE;

    public ScreenWidthQualifier() {
        // pass
    }

    public ScreenWidthQualifier(int value) {
        mValue = value;
    }

    public int getValue() {
        return mValue;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getShortName() {
        return NAME;
    }

    @Override
    public int since() {
        return 13;
    }

    @Override
    public boolean hasFakeValue() {
        return false;
    }

    @Override
    public boolean isValid() {
        return mValue != DEFAULT_SIZE;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        Matcher m = sParsePattern.matcher(value);
        if (m.matches()) {
            String v = m.group(1);

            ScreenWidthQualifier qualifier = getQualifier(v);
            if (qualifier != null) {
                config.setScreenWidthQualifier(qualifier);
                return true;
            }
        }

        return false;
    }

    public static ScreenWidthQualifier getQualifier(String value) {
        try {
            int dp = Integer.parseInt(value);

            ScreenWidthQualifier qualifier = new ScreenWidthQualifier();
            qualifier.mValue = dp;
            return qualifier;

        } catch (NumberFormatException e) {
        }

        return null;
    }

    @Override
    public boolean isMatchFor(ResourceQualifier qualifier) {
        // this is the match only of the current dp value is lower or equal to the
        if (qualifier instanceof ScreenWidthQualifier) {
            return mValue <= ((ScreenWidthQualifier) qualifier).mValue;
        }

        return false;
    }

    @Override
    public boolean isBetterMatchThan(@Nullable ResourceQualifier compareTo,
                                     @NotNull ResourceQualifier reference) {
        if (compareTo == null) {
            return true;
        }

        ScreenWidthQualifier compareQ = (ScreenWidthQualifier) compareTo;
        ScreenWidthQualifier referenceQ = (ScreenWidthQualifier) reference;

        if (compareQ.mValue == referenceQ.mValue) {
            // what we have is already the best possible match (exact match)
            return false;
        } else if (mValue == referenceQ.mValue) {
            // got new exact value, this is the best!
            return true;
        } else {
            // get the qualifier that has the width that is the closest to the reference, but not
            // above. (which is guaranteed when this is called as isMatchFor is called first.
            return mValue > compareQ.mValue;
        }
    }

    @Override
    public String getFolderSegment() {
        return String.format(sPrintPattern, mValue);
    }

    @Override
    public String getShortDisplayValue() {
        if (isValid()) {
            return getFolderSegment();
        }

        return ""; //$NON-NLS-1$
    }

    @Override
    public String getLongDisplayValue() {
        if (isValid()) {
            return getFolderSegment();
        }

        return ""; //$NON-NLS-1$
    }

    @Override
    public int hashCode() {
        return mValue;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ScreenWidthQualifier other = (ScreenWidthQualifier) obj;
        if (mValue != other.mValue) {
            return false;
        }
        return true;
    }
}
