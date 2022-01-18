package com.flipkart.android.proteus.value;

import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;

import com.flipkart.android.proteus.parser.ParseHelper;

import java.util.HashMap;
import java.util.Map;

public class Gravity extends Value {

    private static final Map<Integer, String> GRAVITY_VALUES = new HashMap<>();

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
        StringBuilder sb = new StringBuilder();


        GRAVITY_VALUES.forEach((k, v) -> {
            if ((gravity & k) == k) {
                sb.append(v);
                sb.append("|");
            }
        });
        return sb.substring(0, sb.length() - 1);
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
