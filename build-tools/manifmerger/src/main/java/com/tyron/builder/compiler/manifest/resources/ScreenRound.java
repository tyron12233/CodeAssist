package com.tyron.builder.compiler.manifest.resources;

/**
 * Screen Round enum.
 * <p>This is used in the resource folder names.
 */
public enum ScreenRound implements ResourceEnum {
    NOTROUND("notround", "Not Round", "Not Round screen"), //$NON-NLS-1$
    ROUND(   "round",    "Round",     "Round screen"); //$NON-NLS-1$

    private final String mValue;
    private final String mShortDisplayValue;
    private final String mLongDisplayValue;

    ScreenRound(String value, String displayValue, String longDisplayValue) {
        mValue = value;
        mShortDisplayValue = displayValue;
        mLongDisplayValue = longDisplayValue;
    }

    /**
     * Returns the enum for matching the provided qualifier value.
     * @param value The qualifier value.
     * @return the enum for the qualifier value or null if no matching was found.
     */
    public static ScreenRound getEnum(String value) {
        for (ScreenRound orient : values()) {
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

    public static int getIndex(ScreenRound value) {
        return value == null ? -1 : value.ordinal();
    }

    public static ScreenRound getByIndex(int index) {
        ScreenRound[] values = values();
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

