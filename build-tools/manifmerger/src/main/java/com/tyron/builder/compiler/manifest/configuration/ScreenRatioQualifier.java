package com.tyron.builder.compiler.manifest.configuration;

import com.tyron.builder.compiler.manifest.resources.ResourceEnum;
import com.tyron.builder.compiler.manifest.resources.ScreenRatio;

public class ScreenRatioQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Screen Ratio";

    private ScreenRatio mValue = null;

    public ScreenRatioQualifier() {
    }

    public ScreenRatioQualifier(ScreenRatio value) {
        mValue = value;
    }

    public ScreenRatio getValue() {
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
        return "Ratio";
    }

    @Override
    public int since() {
        return 4;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        ScreenRatio size = ScreenRatio.getEnum(value);
        if (size != null) {
            ScreenRatioQualifier qualifier = new ScreenRatioQualifier(size);
            config.setScreenRatioQualifier(qualifier);
            return true;
        }

        return false;
    }
}
