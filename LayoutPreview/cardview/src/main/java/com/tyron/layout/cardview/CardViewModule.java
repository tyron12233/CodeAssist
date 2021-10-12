package com.tyron.layout.cardview;

import com.flipkart.android.proteus.ProteusBuilder;

public class CardViewModule implements ProteusBuilder.Module {

    private CardViewModule() {

    }

    public static CardViewModule create() {
        return new CardViewModule();
    }

    @Override
    public void registerWith(ProteusBuilder builder) {

    }
}
