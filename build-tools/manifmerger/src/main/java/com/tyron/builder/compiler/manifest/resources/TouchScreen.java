package com.tyron.builder.compiler.manifest.resources;

/**
 * Touch screen enum.
 * <p>This is used in the manifest in the uses-configuration node and in the resource folder names.
 */
public enum TouchScreen implements ResourceEnum {
    NOTOUCH("notouch", "No Touch", "No-touch screen"), //$NON-NLS-1$
    STYLUS("stylus", "Stylus", "Stylus-based touchscreen"), //$NON-NLS-1$
    FINGER("finger", "Finger", "Finger-based touchscreen"); //$NON-NLS-1$

    private final String mValue;
    private final String mShortDisplayValue;
    private final String mLongDisplayValue;

    TouchScreen(String value, String displayValue, String longDisplayValue) {
        mValue = value;
        mShortDisplayValue = displayValue;
        mLongDisplayValue = longDisplayValue;
    }

    /**
     * Returns the enum for matching the provided qualifier value.
     * @param value The qualifier value.
     * @return the enum for the qualifier value or null if no matching was found.
     */
    public static TouchScreen getEnum(String value) {
        for (TouchScreen orient : values()) {
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

    public static int getIndex(TouchScreen touch) {
        return touch == null ? -1 : touch.ordinal();
    }

    public static TouchScreen getByIndex(int index) {
        TouchScreen[] values = values();
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
