package com.tyron.layout.appcompat;

import com.flipkart.android.proteus.ProteusBuilder;
import com.tyron.layout.appcompat.widget.AppBarLayoutParser;
import com.tyron.layout.appcompat.widget.CollapsingToolbarLayoutParser;
import com.tyron.layout.appcompat.widget.CoordinatorLayoutParser;
import com.tyron.layout.appcompat.widget.FloatingActionButtonParser;
import com.tyron.layout.appcompat.widget.MaterialCardViewParser;
import com.tyron.layout.appcompat.widget.RecyclerViewParser;
import com.tyron.layout.appcompat.widget.VisibilityAwareImageButtonParser;

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
        builder.register(new MaterialCardViewParser<>());
        builder.register(new VisibilityAwareImageButtonParser<>());
        builder.register(new FloatingActionButtonParser<>());
        builder.register(new RecyclerViewParser<>());
        AppCompatModuleAttributeHelper.register(builder);
    }
}
