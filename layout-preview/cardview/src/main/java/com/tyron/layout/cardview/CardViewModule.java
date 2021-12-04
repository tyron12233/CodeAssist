package com.tyron.layout.cardview;

import com.flipkart.android.proteus.ProteusBuilder;
import com.tyron.layout.cardview.parser.CardViewParser;

public class CardViewModule implements ProteusBuilder.Module {

    private CardViewModule() {

    }

    public static CardViewModule create() {
        return new CardViewModule();
    }

    @Override
    public void registerWith(ProteusBuilder builder) {
        builder.register(new CardViewParser<>());
    }
}
