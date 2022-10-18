package com.tyron.builder.compiler.manifest.configuration;

import com.tyron.builder.compiler.manifest.resources.Navigation;
import com.tyron.builder.compiler.manifest.resources.ResourceEnum;

/**
 * Resource Qualifier for Navigation Method.
 */
public final class NavigationMethodQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Navigation Method";

    private Navigation mValue;

    public NavigationMethodQualifier() {
        // pass
    }

    public NavigationMethodQualifier(Navigation value) {
        mValue = value;
    }

    public Navigation getValue() {
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
        Navigation method = Navigation.getEnum(value);
        if (method != null) {
            NavigationMethodQualifier qualifier = new NavigationMethodQualifier(method);
            config.setNavigationMethodQualifier(qualifier);
            return true;
        }

        return false;
    }
}
