package com.tyron.builder.compiler.manifest.configuration;

import com.tyron.builder.compiler.manifest.resources.ResourceEnum;
import com.tyron.builder.compiler.manifest.resources.ScreenOrientation;

/**
 * Resource Qualifier for Screen Orientation.
 */
public final class ScreenOrientationQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Screen Orientation";

    private ScreenOrientation mValue = null;

    public ScreenOrientationQualifier() {
    }

    public ScreenOrientationQualifier(ScreenOrientation value) {
        mValue = value;
    }

    public ScreenOrientation getValue() {
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
        return "Orientation";
    }

    @Override
    public int since() {
        return 1;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        ScreenOrientation orientation = ScreenOrientation.getEnum(value);
        if (orientation != null) {
            ScreenOrientationQualifier qualifier = new ScreenOrientationQualifier(orientation);
            config.setScreenOrientationQualifier(qualifier);
            return true;
        }

        return false;
    }
}
