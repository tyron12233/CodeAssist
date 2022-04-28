package com.tyron.builder.compiler.manifest.configuration;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource Qualifier for Mobile Network Code Pixel Density.
 */
public final class NetworkCodeQualifier extends ResourceQualifier {
    /** Default pixel density value. This means the property is not set. */
    private static final int DEFAULT_CODE = -1;

    private static final Pattern sNetworkCodePattern = Pattern.compile("^mnc(\\d{1,3})$"); //$NON-NLS-1$

    private final int mCode;

    public static final String NAME = "Mobile Network Code";

    /**
     * Creates and returns a qualifier from the given folder segment. If the segment is incorrect,
     * <code>null</code> is returned.
     * @param segment the folder segment from which to create a qualifier.
     * @return a new {@link CountryCodeQualifier} object or <code>null</code>
     */
    public static NetworkCodeQualifier getQualifier(String segment) {
        Matcher m = sNetworkCodePattern.matcher(segment);
        if (m.matches()) {
            String v = m.group(1);

            int code = -1;
            try {
                code = Integer.parseInt(v);
            } catch (NumberFormatException e) {
                // looks like the string we extracted wasn't a valid number.
                return null;
            }

            NetworkCodeQualifier qualifier = new NetworkCodeQualifier(code);
            return qualifier;
        }

        return null;
    }

    /**
     * Returns the folder name segment for the given value. This is equivalent to calling
     * {@link #toString()} on a {@link NetworkCodeQualifier} object.
     * @param code the value of the qualifier, as returned by {@link #getCode()}.
     */
    public static String getFolderSegment(int code) {
        if (code != DEFAULT_CODE && code >= 1 && code <= 999) { // code is 1-3 digit.
            return String.format(Locale.US, "mnc%1$03d", code); //$NON-NLS-1$
        }

        return ""; //$NON-NLS-1$
    }

    public NetworkCodeQualifier() {
        this(DEFAULT_CODE);
    }

    public NetworkCodeQualifier(int code) {
        mCode = code;
    }

    public int getCode() {
        return mCode;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getShortName() {
        return "Network Code";
    }

    @Override
    public int since() {
        return 1;
    }

    @Override
    public boolean isValid() {
        return mCode != DEFAULT_CODE;
    }

    @Override
    public boolean hasFakeValue() {
        return false;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        Matcher m = sNetworkCodePattern.matcher(value);
        if (m.matches()) {
            String v = m.group(1);

            int code = -1;
            try {
                code = Integer.parseInt(v);
            } catch (NumberFormatException e) {
                // looks like the string we extracted wasn't a valid number.
                return false;
            }

            NetworkCodeQualifier qualifier = new NetworkCodeQualifier(code);
            config.setNetworkCodeQualifier(qualifier);
            return true;
        }

        return false;
    }

    @Override
    public boolean equals(Object qualifier) {
        if (qualifier instanceof NetworkCodeQualifier) {
            return mCode == ((NetworkCodeQualifier)qualifier).mCode;
        }

        return false;
    }

    @Override
    public int hashCode() {
        return mCode;
    }

    /**
     * Returns the string used to represent this qualifier in the folder name.
     */
    @Override
    public String getFolderSegment() {
        return getFolderSegment(mCode);
    }

    @Override
    public String getShortDisplayValue() {
        if (mCode != DEFAULT_CODE) {
            return String.format("MNC %1$d", mCode);
        }

        return ""; //$NON-NLS-1$
    }

    @Override
    public String getLongDisplayValue() {
        return getShortDisplayValue();
    }

}