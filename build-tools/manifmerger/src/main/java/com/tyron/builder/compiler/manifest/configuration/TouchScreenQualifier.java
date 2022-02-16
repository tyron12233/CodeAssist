package com.tyron.builder.compiler.manifest.configuration;

import com.tyron.builder.compiler.manifest.resources.ResourceEnum;
import com.tyron.builder.compiler.manifest.resources.TouchScreen;

/**
 * Resource Qualifier for Touch Screen type.
 */
public final class TouchScreenQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Touch Screen";

    private TouchScreen mValue;

    public TouchScreenQualifier() {
        // pass
    }

    public TouchScreenQualifier(TouchScreen touchValue) {
        mValue = touchValue;
    }

    public TouchScreen getValue() {
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
        return 1;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        TouchScreen type = TouchScreen.getEnum(value);
        if (type != null) {
            TouchScreenQualifier qualifier = new TouchScreenQualifier();
            qualifier.mValue = type;
            config.setTouchTypeQualifier(qualifier);
            return true;
        }

        return false;
    }
}

