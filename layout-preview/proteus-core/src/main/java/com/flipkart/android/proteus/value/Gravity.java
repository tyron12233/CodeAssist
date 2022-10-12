package com.flipkart.android.proteus.value;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.util.GravityIntMapping;

public class Gravity extends Value {
    private final GravityIntMapping gravityIntMapping = new GravityIntMapping();

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
