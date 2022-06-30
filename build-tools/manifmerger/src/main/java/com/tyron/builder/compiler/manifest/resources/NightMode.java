package com.tyron.builder.compiler.manifest.resources;

/**
 * Night enum.
 * <p>This is used in the resource folder names.
 */
public enum NightMode implements ResourceEnum {
    NOTNIGHT("notnight", "Not Night", "Day time"),
    NIGHT("night", "Night", "Night time");

    private final String mValue;
    private final String mShortDisplayValue;
    private final String mLongDisplayValue;

    NightMode(String value, String shortDisplayValue, String longDisplayValue) {
        mValue = value;
        mShortDisplayValue = shortDisplayValue;
        mLongDisplayValue = longDisplayValue;
    }

    /**
     * Returns the enum for matching the provided qualifier value.
     * @param value The qualifier value.
     * @return the enum for the qualifier value or null if no matching was found.
     */
    public static NightMode getEnum(String value) {
        for (NightMode mode : values()) {
            if (mode.mValue.equals(value)) {
                return mode;
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

    public static int getIndex(NightMode value) {
        return value == null ? -1 : value.ordinal();
    }

    public static NightMode getByIndex(int index) {
        NightMode[] values = values();
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
