package com.tyron.layoutpreview.custom;

import static com.google.common.truth.Truth.assertThat;

import android.view.View;

import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.model.Format;
import com.tyron.layoutpreview.parser.WrapperUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class TestCustomViewAttribute {

    CustomView customView;


    @Before
    public void setup() {
        customView = getTestView();
    }

    private CustomView getTestView() {
        CustomView view = new CustomView();
        view.setType("androidx.cardview.widget.CardView");
        view.setParentType("FrameLayout");

        Attribute attribute = Attribute.builder()
                .setMethodName("setCardBackgroundColor")
                .setXmlName("app:cardBackgroundColor")
                .setParameters(int.class)
                .addFormat(Format.COLOR)
                .build();

        Attribute cornerRadius = Attribute.builder()
                .setMethodName("setRadius")
                .setXmlName("app:cardCornerRadius")
                .addFormat(Format.DIMENSION)
                .setParameters(float.class)
                .setDimension(true)
                .build();

        view.setAttributes(Arrays.asList(attribute, cornerRadius));
        return view;
    }

    @Test
    public void testCustomView() throws Exception {
        Class<? extends View> clazz = Class.forName(customView.getType())
                .asSubclass(View.class);

        for (Attribute attribute : customView.getAttributes()) {
            if (attribute.isLayoutParams()) {
                continue;
            }
            Method method = WrapperUtils.getMethod(clazz, attribute.getMethodName(), WrapperUtils.getParameters(attribute.getParameters()));
            assertThat(method)
                    .isNotNull();
        }
    }
}
