package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.KeyboardState;
import com.tyron.builder.compiler.manifest.resources.ResourceEnum;

/**
 * Resource Qualifier for keyboard state.
 */
public final class KeyboardStateQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Keyboard State";

    private KeyboardState mValue = null;

    public KeyboardStateQualifier() {
        // pass
    }

    public KeyboardStateQualifier(KeyboardState value) {
        mValue = value;
    }

    public KeyboardState getValue() {
        return mValue;
    }

    @Override
    public ResourceEnum getEnumValue() {
        return mValue;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getShortName() {
        return "Keyboard";
    }

    @Override
    public int since() {
        return 1;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        KeyboardState orientation = KeyboardState.getEnum(value);
        if (orientation != null) {
            KeyboardStateQualifier qualifier = new KeyboardStateQualifier();
            qualifier.mValue = orientation;
            config.setKeyboardStateQualifier(qualifier);
            return true;
        }

        return false;
    }

    @Override
    public boolean isMatchFor(ResourceQualifier qualifier) {
        if (qualifier instanceof KeyboardStateQualifier) {
            KeyboardStateQualifier referenceQualifier = (KeyboardStateQualifier)qualifier;

            // special case where EXPOSED can be used for SOFT
            if (referenceQualifier.mValue == KeyboardState.SOFT &&
                    mValue == KeyboardState.EXPOSED) {
                return true;
            }

            return referenceQualifier.mValue == mValue;
        }

        return false;
    }

    @Override
    public boolean isBetterMatchThan(@Nullable ResourceQualifier compareTo,
                                     @NotNull ResourceQualifier reference) {
        if (compareTo == null) {
            return true;
        }

        KeyboardStateQualifier compareQualifier = (KeyboardStateQualifier) compareTo;
        KeyboardStateQualifier referenceQualifier = (KeyboardStateQualifier) reference;

        if (referenceQualifier.mValue
                == KeyboardState.SOFT) { // only case where there could be a better qualifier

            // only return true if it's a better value.
            if (compareQualifier.mValue == KeyboardState.EXPOSED && mValue == KeyboardState.SOFT) {
                return true;
            }
        }

        return false;
    }
}
