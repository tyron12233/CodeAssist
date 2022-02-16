package com.tyron.builder.compiler.manifest.resources;

/**
 * Layout Direction enum.
 */
public enum LayoutDirection implements ResourceEnum {
    LTR("ldltr", "LTR", "Left To Right"), //$NON-NLS-1$
    RTL("ldrtl", "RTL", "Right To Left"); //$NON-NLS-1$

    private final String mValue;
    private final String mShortDisplayValue;
    private final String mLongDisplayValue;

    LayoutDirection(String value, String shortDisplayValue, String longDisplayValue) {
        mValue = value;
        mShortDisplayValue = shortDisplayValue;
        mLongDisplayValue = longDisplayValue;
    }

    /**
     * Returns the enum for matching the provided qualifier value.
     * @param value The qualifier value.
     * @return the enum for the qualifier value or null if no matching was found.
     */
    public static LayoutDirection getEnum(String value) {
        for (LayoutDirection orient : values()) {
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

    public static int getIndex(LayoutDirection orientation) {
        return orientation == null ? -1 : orientation.ordinal();
    }

    public static LayoutDirection getByIndex(int index) {
        LayoutDirection[] values = values();
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
