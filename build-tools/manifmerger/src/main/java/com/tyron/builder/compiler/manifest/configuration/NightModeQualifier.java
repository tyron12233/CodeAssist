package com.tyron.builder.compiler.manifest.configuration;

import com.tyron.builder.compiler.manifest.resources.NightMode;
import com.tyron.builder.compiler.manifest.resources.ResourceEnum;

/**
 * Resource Qualifier for Navigation Method.
 */
public final class NightModeQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Night Mode";

    private NightMode mValue;

    public NightModeQualifier() {
        // pass
    }

    public NightModeQualifier(NightMode value) {
        mValue = value;
    }

    public NightMode getValue() {
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
        return "Night Mode";
    }

    @Override
    public int since() {
        return 8;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        NightMode mode = NightMode.getEnum(value);
        if (mode != null) {
            NightModeQualifier qualifier = new NightModeQualifier(mode);
            config.setNightModeQualifier(qualifier);
            return true;
        }

        return false;
    }
}
