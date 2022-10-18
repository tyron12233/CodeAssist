package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource Qualifier for Screen Dimension.
 */
public final class ScreenDimensionQualifier extends ResourceQualifier {
    /** Default screen size value. This means the property is not set */
    static final int DEFAULT_SIZE = -1;

    private static final Pattern sDimensionPattern = Pattern.compile(
            "^(\\d+)x(\\d+)$"); //$NON-NLS-1$

    public static final String NAME = "Screen Dimension";

    /**
     * Screen size 1 value. This is not size X or Y because the folder name always contains the
     * biggest size first. So if the qualifier is 400x200, size 1 will always be 400 but that will
     * be X in landscape and Y in portrait. Default value is <code>DEFAULT_SIZE</code>
     */
    private final int mValue1;

    /**
     * Screen size 2 value. This is not size X or Y because the folder name always contains the
     * biggest size first. So if the qualifier is 400x200, size 2 will always be 200 but that will
     * be Y in landscape and X in portrait. Default value is <code>DEFAULT_SIZE</code>
     */
    private final int mValue2;

    private final String mShortDisplayValue;

    public ScreenDimensionQualifier(int value1, int value2) {
        mValue1 = value1;
        mValue2 = value2;
        mShortDisplayValue = String.format("%1$dx%2$d", value1, value2);
    }

    public ScreenDimensionQualifier() {
        this(DEFAULT_SIZE, DEFAULT_SIZE);
    }

    public int getValue1() {
        return mValue1;
    }

    public int getValue2() {
        return mValue2;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getShortName() {
        return "Dimension";
    }

    @Override
    public int since() {
        return 1;
    }

    @Override
    public boolean deprecated() {
        return true;
    }

    @Override
    public boolean isValid() {
        return mValue1 != DEFAULT_SIZE && mValue2 != DEFAULT_SIZE;
    }

    @Override
    public boolean hasFakeValue() {
        return false;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        Matcher m = sDimensionPattern.matcher(value);
        if (m.matches()) {
            String d1 = m.group(1);
            String d2 = m.group(2);

            ScreenDimensionQualifier qualifier = getQualifier(d1, d2);
            if (qualifier != null) {
                config.setScreenDimensionQualifier(qualifier);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object qualifier) {
        if (qualifier instanceof ScreenDimensionQualifier) {
            ScreenDimensionQualifier q = (ScreenDimensionQualifier)qualifier;
            return (mValue1 == q.mValue1 && mValue2 == q.mValue2);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    public static ScreenDimensionQualifier getQualifier(String size1, String size2) {
        try {
            int s1 = Integer.parseInt(size1);
            int s2 = Integer.parseInt(size2);

            int value1;
            int value2;
            if (s1 > s2) {
                value1 = s1;
                value2 = s2;
            } else {
                value1 = s2;
                value2 = s1;
            }

            return new ScreenDimensionQualifier(value1, value2);
        } catch (NumberFormatException e) {
            // looks like the string we extracted wasn't a valid number.
        }

        return null;
    }

    /** Returns the string used to represent this qualifier in the folder name. */
    @Override
    @NotNull
    public String getFolderSegment() {
        return mShortDisplayValue;
    }

    @Override
    @NotNull
    public String getShortDisplayValue() {
        if (isValid()) {
            return getFolderSegment();
        }

        return ""; //$NON-NLS-1$
    }

    @Override
    @NotNull
    public String getLongDisplayValue() {
        if (isValid()) {
            return "Screen resolution " + getFolderSegment();
        }

        return ""; //$NON-NLS-1$
    }
}
