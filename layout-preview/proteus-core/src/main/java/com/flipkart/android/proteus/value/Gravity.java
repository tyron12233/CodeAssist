package com.flipkart.android.proteus.value;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.util.GravityIntMapping;

import java.util.LinkedHashMap;
import java.util.Map;

public class Gravity extends Value {

    private static final Map<Integer, String> GRAVITY_VALUES = new LinkedHashMap<>();
    private final GravityIntMapping gravityIntMapping = new GravityIntMapping();

    static {
        GRAVITY_VALUES.put(android.view.Gravity.LEFT, "left");
        GRAVITY_VALUES.put(android.view.Gravity.RIGHT, "right");
        GRAVITY_VALUES.put(android.view.Gravity.TOP, "top");
        GRAVITY_VALUES.put(android.view.Gravity.BOTTOM, "bottom");
        GRAVITY_VALUES.put(android.view.Gravity.START, "start");
        GRAVITY_VALUES.put(android.view.Gravity.END, "end");
        GRAVITY_VALUES.put(android.view.Gravity.CENTER, "center");
        GRAVITY_VALUES.put(android.view.Gravity.CENTER_HORIZONTAL, "center_horizontal");
        GRAVITY_VALUES.put(android.view.Gravity.CENTER_VERTICAL, "center_vertical");
    }

    private final int gravity;

    public static boolean isGravity(String string) {
        try {
            int gravity = ParseHelper.parseGravity(string);
            return gravity != android.view.Gravity.NO_GRAVITY;
        } catch (Throwable e) {
            return false;
        }
    }

    public Gravity(int gravity) {
        this.gravity = gravity;
    }

    public static Gravity of(int value) {
        return new Gravity(value);
    }

    @Override
    public String getAsString() {
        return String.join("|", gravityIntMapping.fromIntValue(gravity));
    }

    @Override
    public int getAsInt() {
        return gravity;
    }

    @NonNull
    @Override
    public String toString() {
        return getAsString();
    }

    @Override
    public Value copy() {
        return new Gravity(gravity);
    }
}
