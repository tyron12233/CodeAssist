package com.tyron.layout.constraintlayout;

import com.flipkart.android.proteus.ProteusBuilder;
import com.tyron.layout.constraintlayout.widget.ConstraintLayoutParser;

public class ConstraintLayoutModule implements ProteusBuilder.Module {

    private ConstraintLayoutModule() {

    }

    public static ConstraintLayoutModule create() {
        return new ConstraintLayoutModule();
    }

    @Override
    public void registerWith(ProteusBuilder builder) {
        builder.register(new ConstraintLayoutParser<>());
    }
}
