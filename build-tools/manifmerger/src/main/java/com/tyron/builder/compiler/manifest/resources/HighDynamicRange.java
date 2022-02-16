package com.tyron.builder.compiler.manifest.resources;

/**
 * High Dynamic Range
 *
 * <p>This is used in the resource folder names.
 */
public enum HighDynamicRange implements ResourceEnum {
    HIGHDR("highdr", "High Dynamic Range", "High Dynamic Rage"),
    LOWDR("lowdr", "Low Dynamic Range", "Low Dynamic Range");

    private final String mValue;
    private final String mShortDisplayValue;
    private final String mLongDisplayValue;

    HighDynamicRange(String value, String displayValue, String longDisplayValue) {
        mValue = value;
        mShortDisplayValue = displayValue;
        mLongDisplayValue = longDisplayValue;
    }

    /**
     * Returns the enum for matching the provided qualifier value.
     *
     * @param value The qualifier value.
     * @return the enum for the qualifier value or null if no matching was found.
     */
    public static HighDynamicRange getEnum(String value) {
        for (HighDynamicRange orient : values()) {
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

    public static int getIndex(HighDynamicRange value) {
        return value == null ? -1 : value.ordinal();
    }

    public static HighDynamicRange getByIndex(int index) {
        HighDynamicRange[] values = values();
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
