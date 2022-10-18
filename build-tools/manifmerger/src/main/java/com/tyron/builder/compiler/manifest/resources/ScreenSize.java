package com.tyron.builder.compiler.manifest.resources;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Screen size enum.
 * <p>This is used in the manifest in the uses-configuration node and in the resource folder names.
 */
public enum ScreenSize implements ResourceEnum {
    SMALL("small", "Small", "Small Screen"), //$NON-NLS-1$
    NORMAL("normal", "Normal", "Normal Screen"), //$NON-NLS-1$
    LARGE("large", "Large", "Large Screen"), //$NON-NLS-1$
    XLARGE("xlarge", "X-Large", "Extra Large Screen"); //$NON-NLS-1$

    private final String mValue;
    private final String mShortDisplayValue;
    private final String mLongDisplayValue;

    ScreenSize(String value, String shortDisplayValue, String longDisplayValue) {
        mValue = value;
        mShortDisplayValue = shortDisplayValue;
        mLongDisplayValue = longDisplayValue;
    }

    /**
     * Returns the enum for matching the provided qualifier value.
     * @param value The qualifier value.
     * @return the enum for the qualifier value or null if no matching was found.
     */
    public static ScreenSize getEnum(String value) {
        for (ScreenSize orient : values()) {
            if (orient.mValue.equals(value)) {
                return orient;
            }
        }

        return null;
    }

    @Override
    public String getResourceValue() {
        return mValue;
    }

    @Override
    public String getShortDisplayValue() {
        return mShortDisplayValue;
    }

    @Override
    public String getLongDisplayValue() {
        return mLongDisplayValue;
    }

    public static int getIndex(@Nullable ScreenSize value) {
        return value == null ? -1 : value.ordinal();
    }

    public static ScreenSize getByIndex(int index) {
        ScreenSize[] values = values();
        if (index >= 0 && index < values.length) {
            return values[index];
        }
        return null;
    }

    /**
     * Get the resource bucket value that corresponds to the given size in inches.
     *
     * @param diagonalSize Diagonal Screen size in inches.
     *                     If null, a default diagonal size is used
     */
    @NotNull
    public static ScreenSize getScreenSize(@Nullable Double diagonalSize) {
        if (diagonalSize == null) {
            return ScreenSize.NORMAL;
        }

        // Density-independent pixel (dp) : The density-independent pixel is
        // equivalent to one physical pixel on a 160 dpi screen,
        // which is the baseline density assumed by the system for a
        // "medium" density screen.
        // Android 8.1 Compatibility Definition, section 7.1
        double diagonalDp = 160.0 * diagonalSize;

        // Set the Screen Size
        if (diagonalDp >= 1200) return XLARGE;
        if (diagonalDp >=  800) return LARGE;
        if (diagonalDp >=  568) return NORMAL;

        return SMALL;
    }

    @Override
    public boolean isFakeValue() {
        return false;
    }

    @Override
    public boolean isValidValueForDevice() {
        return true;
    }

}