package com.tyron.builder.compiler.manifest.resources;

/**
 * Navigation enum.
 * <p>This is used in the manifest in the uses-configuration node and in the resource folder names.
 */
public enum Navigation implements ResourceEnum {
    NONAV("nonav", "None", "No navigation"), //$NON-NLS-1$
    DPAD("dpad", "D-pad", "D-pad navigation"), //$NON-NLS-1$
    TRACKBALL("trackball", "Trackball", "Trackball navigation"), //$NON-NLS-1$
    WHEEL("wheel", "Wheel", "Wheel navigation"); //$NON-NLS-1$

    private final String mValue;
    private final String mShortDisplayValue;
    private final String mLongDisplayValue;

    Navigation(String value, String shortDisplayValue, String longDisplayValue) {
        mValue = value;
        mShortDisplayValue = shortDisplayValue;
        mLongDisplayValue = longDisplayValue;
    }

    /**
     * Returns the enum for matching the provided qualifier value.
     * @param value The qualifier value.
     * @return the enum for the qualifier value or null if no matching was found.
     */
    public static Navigation getEnum(String value) {
        for (Navigation nav : values()) {
            if (nav.mValue.equals(value)) {
                return nav;
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

    public static int getIndex(Navigation value) {
        return value == null ? -1 : value.ordinal();
    }

    public static Navigation getByIndex(int index) {
        Navigation[] values = values();
        if (index >= 0 && index < values.length) {
            return values[index];
        }
        return null;
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

