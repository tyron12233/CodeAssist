package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceEnum;
import com.tyron.builder.compiler.manifest.resources.WideGamutColor;

public class WideGamutColorQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Color Gamut";

    @Nullable private WideGamutColor mValue = null;

    public WideGamutColorQualifier() {}

    public WideGamutColorQualifier(@Nullable WideGamutColor value) {
        mValue = value;
    }

    public WideGamutColor getValue() {
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
        return 26;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        WideGamutColor enumValue = WideGamutColor.getEnum(value);
        if (enumValue != null) {
            WideGamutColorQualifier qualifier = new WideGamutColorQualifier(enumValue);
            config.setWideColorGamutQualifier(qualifier);
            return true;
        }

        return false;
    }
}
