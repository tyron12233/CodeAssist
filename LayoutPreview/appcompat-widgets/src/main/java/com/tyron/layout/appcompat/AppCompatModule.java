package com.tyron.layout.appcompat;

import com.flipkart.android.proteus.ProteusBuilder;
import com.tyron.layout.appcompat.widget.AppBarLayoutParser;
import com.tyron.layout.appcompat.widget.CollapsingToolbarLayoutParser;
import com.tyron.layout.appcompat.widget.CoordinatorLayoutParser;

public class AppCompatModule implements ProteusBuilder.Module {

    private AppCompatModule() {
    }

    public static AppCompatModule create() {
        return new AppCompatModule();
    }

    @Override
    public void registerWith(ProteusBuilder builder) {
        builder.register(new AppBarLayoutParser<>());
        builder.register(new CollapsingToolbarLayoutParser<>());
        builder.register(new CoordinatorLayoutParser<>());
        AppCompatModuleAttributeHelper.register(builder);
    }
}
