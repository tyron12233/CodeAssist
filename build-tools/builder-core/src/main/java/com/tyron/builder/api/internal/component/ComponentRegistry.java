package com.tyron.builder.api.internal.component;

/**
 * TODO - merge this and the component container
 */
public class ComponentRegistry {
    private BuildableJavaComponent mainComponent;

    public BuildableJavaComponent getMainComponent() {
        return mainComponent;
    }

    public void setMainComponent(BuildableJavaComponent mainComponent) {
        this.mainComponent = mainComponent;
    }
}
