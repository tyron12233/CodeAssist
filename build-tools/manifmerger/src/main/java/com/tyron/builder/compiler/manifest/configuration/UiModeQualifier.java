package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceEnum;
import com.tyron.builder.compiler.manifest.resources.UiMode;

/**
 * Resource Qualifier for UI Mode.
 */
public final class UiModeQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "UI Mode";

    private UiMode mValue;

    public UiModeQualifier() {
        // pass
    }

    public UiModeQualifier(UiMode value) {
        mValue = value;
    }

    public UiMode getValue() {
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
        return NAME;
    }

    @Override
    public int since() {
        if (mValue != null) {
            return mValue.since();
        }
        return 8;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        UiMode mode = UiMode.getEnum(value);
        if (mode != null) {
            UiModeQualifier qualifier = new UiModeQualifier(mode);
            config.setUiModeQualifier(qualifier);
            return true;
        }

        return false;
    }

    @Override
    public boolean isMatchFor(ResourceQualifier qualifier) {
        // only normal is a match for all UI mode, because it's not an actual mode.
        if (mValue == UiMode.NORMAL) {
            return true;
        }

        // others must be an exact match
        return ((UiModeQualifier)qualifier).mValue == mValue;
    }

    @Override
    public boolean isBetterMatchThan(@Nullable ResourceQualifier compareTo,
                                     @NotNull ResourceQualifier reference) {
        if (compareTo == null) {
            return true;
        }

        UiModeQualifier compareQualifier = (UiModeQualifier) compareTo;
        UiModeQualifier referenceQualifier = (UiModeQualifier) reference;

        if (compareQualifier.getValue() == referenceQualifier.getValue()) {
            // what we have is already the best possible match (exact match)
            return false;
        } else if (mValue == referenceQualifier.mValue) {
            // got new exact value, this is the best!
            return true;
        } else if (mValue == UiMode.NORMAL) {
            // else "normal" can be a match in case there's no exact match
            return true;
        }

        return false;
    }
}
