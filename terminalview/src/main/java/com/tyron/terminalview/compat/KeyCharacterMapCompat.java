package com.tyron.terminalview.compat;

import android.view.KeyCharacterMap;

public abstract class KeyCharacterMapCompat {
    public static final int MODIFIER_BEHAVIOR_CHORDED = 0;
    public static final int MODIFIER_BEHAVIOR_CHORDED_OR_TOGGLED = 1;

    public static KeyCharacterMapCompat wrap(Object map) {
        if (map != null) {
            if (AndroidCompat.SDK >= 11) {
                return new KeyCharacterMapApi11OrLater(map);
            }
        }
        return null;
    }

    private static class KeyCharacterMapApi11OrLater
        extends KeyCharacterMapCompat {
        private KeyCharacterMap mMap;
        public KeyCharacterMapApi11OrLater(Object map) {
            mMap = (KeyCharacterMap) map;
        }
        public int getModifierBehaviour() {
            return mMap.getModifierBehavior();
        }
    }

    public abstract int getModifierBehaviour();
}