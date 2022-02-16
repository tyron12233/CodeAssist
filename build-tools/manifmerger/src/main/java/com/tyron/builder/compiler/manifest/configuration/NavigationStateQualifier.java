package com.tyron.builder.compiler.manifest.configuration;

import com.tyron.builder.compiler.manifest.resources.NavigationState;
import com.tyron.builder.compiler.manifest.resources.ResourceEnum;

/**
 * Resource Qualifier for navigation state.
 */
public final class NavigationStateQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Navigation State";

    private NavigationState mValue = null;

    public NavigationStateQualifier() {
        // pass
    }

    public NavigationStateQualifier(NavigationState value) {
        mValue = value;
    }

    public NavigationState getValue() {
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
        NavigationState state = NavigationState.getEnum(value);
        if (state != null) {
            NavigationStateQualifier qualifier = new NavigationStateQualifier();
            qualifier.mValue = state;
            config.setNavigationStateQualifier(qualifier);
            return true;
        }

        return false;
    }
}
