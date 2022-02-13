package com.tyron.builder.compiler.manifest.resources;

/**
 * UI Mode enum.
 * <p>This is used in the resource folder names.
 */
public enum UiMode implements ResourceEnum {
    NORMAL("", "Normal", 1),
    CAR("car", "Car Dock", 8),
    DESK("desk", "Desk Dock", 8),
    TELEVISION("television", "Television", 13),
    APPLIANCE("appliance", "Appliance", 16),
    WATCH("watch", "Watch", 20),
    VR_HEADSET("vrheadset", "VR Headset", 26);

    private final String mValue;
    private final String mDisplayValue;
    private final int mSince;

    UiMode(String value, String display, int since) {
        mValue = value;
        mDisplayValue = display;
        mSince = since;
    }

    /**
     * Returns the enum for matching the provided qualifier value.
     * @param value The qualifier value.
     * @return the enum for the qualifier value or null if no matching was found.
     */
    public static UiMode getEnum(String value) {
        for (UiMode mode : values()) {
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

    public int since() {
        return mSince;
    }

    @Override
    public String getShortDisplayValue() {
        return mDisplayValue;
    }

    @Override
    public String getLongDisplayValue() {
        return mDisplayValue;
    }

    public static int getIndex(UiMode value) {
        return value == null ? -1 : value.ordinal();
    }

    public static UiMode getByIndex(int index) {
        UiMode[] values = values();
        if (index >= 0 && index < values.length) {
            return values[index];
        }
        return null;
    }

    @Override
    public boolean isFakeValue() {
        return this == NORMAL; // NORMAL is not a real enum. it's used for internal state only.
    }

    @Override
    public boolean isValidValueForDevice() {
        return this != NORMAL;
    }
}

