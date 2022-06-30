package com.tyron.builder.compiler.manifest.resources;

/**
 * Navigation state enum.
 * <p>This is used in the resource folder names.
 */
public enum NavigationState implements ResourceEnum {
    EXPOSED("navexposed", "Exposed", "Exposed navigation"), //$NON-NLS-1$
    HIDDEN("navhidden", "Hidden", "Hidden navigation");    //$NON-NLS-1$

    private final String mValue;
    private final String mShortDisplayValue;
    private final String mLongDisplayValue;

    NavigationState(String value, String shortDisplayValue, String longDisplayValue) {
        mValue = value;
        mShortDisplayValue = shortDisplayValue;
        mLongDisplayValue = longDisplayValue;
    }

    /**
     * Returns the enum for matching the provided qualifier value.
     * @param value The qualifier value.
     * @return the enum for the qualifier value or null if no matching was found.
     */
    public static NavigationState getEnum(String value) {
        for (NavigationState state : values()) {
            if (state.mValue.equals(value)) {
                return state;
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

    public static int getIndex(NavigationState value) {
        return value == null ? -1 : value.ordinal();
    }

    public static NavigationState getByIndex(int index) {
        NavigationState[] values = values();
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
