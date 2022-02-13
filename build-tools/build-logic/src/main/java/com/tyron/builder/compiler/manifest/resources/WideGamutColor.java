package com.tyron.builder.compiler.manifest.resources;

/**
 * Wide Color Gamut
 *
 * <p>This is used in the resource folder names.
 */
public enum WideGamutColor implements ResourceEnum {
    WIDECG("widecg", "Wide Color Gamut", "Wide Color Gamut"),
    NOWIDECG("nowidecg", "No Wide Color Gamut", "No Wide Color Gamut");

    private final String mValue;
    private final String mShortDisplayValue;
    private final String mLongDisplayValue;

    WideGamutColor(String value, String displayValue, String longDisplayValue) {
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
    public static WideGamutColor getEnum(String value) {
        for (WideGamutColor orient : values()) {
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

    public static int getIndex(WideGamutColor value) {
        return value == null ? -1 : value.ordinal();
    }

    public static WideGamutColor getByIndex(int index) {
        WideGamutColor[] values = values();
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
