package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.HighDynamicRange;
import com.tyron.builder.compiler.manifest.resources.ResourceEnum;

public class HighDynamicRangeQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Dynamic Range";

    @Nullable private HighDynamicRange mValue = null;

    public HighDynamicRangeQualifier() {}

    public HighDynamicRangeQualifier(@Nullable HighDynamicRange value) {
        mValue = value;
    }

    public HighDynamicRange getValue() {
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
        return "HDR";
    }

    @Override
    public int since() {
        return 26;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        HighDynamicRange enumValue = HighDynamicRange.getEnum(value);
        if (enumValue != null) {
            HighDynamicRangeQualifier qualifier = new HighDynamicRangeQualifier(enumValue);
            config.setHighDynamicRangeQualifier(qualifier);
            return true;
        }

        return false;
    }
}
