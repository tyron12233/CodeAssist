package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceEnum;
import com.tyron.builder.compiler.manifest.resources.ScreenRound;

public class ScreenRoundQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Screen Roundness";

    @Nullable
    private ScreenRound mValue = null;

    public ScreenRoundQualifier() {
    }

    public ScreenRoundQualifier(@Nullable ScreenRound value) {
        mValue = value;
    }

    public ScreenRound getValue() {
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
        return "Roundness";
    }

    @Override
    public int since() {
        return 23;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        ScreenRound roundness = ScreenRound.getEnum(value);
        if (roundness != null) {
            ScreenRoundQualifier qualifier = new ScreenRoundQualifier(roundness);
            config.setScreenRoundQualifier(qualifier);
            return true;
        }

        return false;
    }
}
