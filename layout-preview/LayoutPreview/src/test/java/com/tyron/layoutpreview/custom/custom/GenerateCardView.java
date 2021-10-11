package com.tyron.layoutpreview.custom.custom;

import androidx.annotation.NonNull;

import com.tyron.layoutpreview.custom.AbstractCustomViewTest;
import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.model.Format;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


public class GenerateCardView extends AbstractCustomViewTest {

    @NonNull
    @Override
    public List<CustomView> getCustomViews() {
        return Collections.singletonList(getCardView());
    }

    @Override
    protected boolean isGenerated() {
        return true;
    }

    @Override
    protected String getOutputName() {
        return "CardView.json";
    }

    @NonNull
    @Override
    public ClassLoader getClassLoader() {
        return Objects.requireNonNull(getClass().getClassLoader());
    }

    private CustomView getCardView() {
        CustomView card = new CustomView();
        card.setType("androidx.cardview.widget.CardView");
        card.setParentType("android.widget.FrameLayout");
        card.setViewGroup(true);

        Attribute cardBackgroundColor = Attribute.builder()
                .addFormat(Format.COLOR)
                .setXmlName("app:cardBackgroundColor")
                .setMethodName("setCardBackgroundColor")
                .setParameters(int.class)
                .build();
        Attribute cardCornerRadius = Attribute.builder()
                .addFormat(Format.DIMENSION)
                .setXmlName("app:cardCornerRadius")
                .setMethodName("setRadius")
                .setParameters(float.class)
                .build();

        card.setAttributes(Arrays.asList(cardBackgroundColor, cardCornerRadius));
        return card;
    }
}
