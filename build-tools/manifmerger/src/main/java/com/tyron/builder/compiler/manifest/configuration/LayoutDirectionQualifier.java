package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;

import com.tyron.builder.compiler.manifest.resources.LayoutDirection;
import com.tyron.builder.compiler.manifest.resources.ResourceEnum;

/**
 * Resource Qualifier for layout direction. values can be "ltr", or "rtl"
 */
public class LayoutDirectionQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Layout Direction";

    private static final LayoutDirectionQualifier NULL_QUALIFIER = new LayoutDirectionQualifier();

    private LayoutDirection mValue = null;


    public LayoutDirectionQualifier() {
    }

    public LayoutDirectionQualifier(LayoutDirection value) {
        mValue = value;
    }

    public LayoutDirection getValue() {
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
        return 17;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        LayoutDirection ld = LayoutDirection.getEnum(value);
        if (ld != null) {
            LayoutDirectionQualifier qualifier = new LayoutDirectionQualifier(ld);
            config.setLayoutDirectionQualifier(qualifier);
            return true;
        }

        return false;
    }

    @Override
    @NotNull
    public ResourceQualifier getNullQualifier() {
        return NULL_QUALIFIER;
    }
}
