package com.tyron.layoutpreview.custom;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.parser.WrapperUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TestCustomViewJson {

    private static final String TEST_JSON = "{\"type\":\"androidx.cardview.widget.CardView\",\"parentType\":\"FrameLayout\",\"attributes\":[{\"methodName\":\"setCardBackgroundColor\",\"xmlName\":\"app:cardBackgroundColor\",\"parameters\":[\"int\"],\"xmlParameterOffset\":0,\"isDimension\":false,\"isLayoutParams\":false,\"formats\":[\"COLOR\"]},{\"methodName\":\"setRadius\",\"xmlName\":\"app:cardCornerRadius\",\"parameters\":[\"float\"],\"xmlParameterOffset\":0,\"isDimension\":true,\"isLayoutParams\":false,\"formats\":[\"DIMENSION\"]}]}";
    private static final String EXPECTED_TYPE = "androidx.cardview.widget.CardView";
    private static final String EXPECTED_PARENT_TYPE = "FrameLayout";

    private CustomView mCustomView;

    @Before
    public void setup() {
        mCustomView = CustomView.fromJson(TEST_JSON);
    }

    @Test
    public void testJsonToCustomView() {
        assertThat(mCustomView).isNotNull();
        assertThat(mCustomView.getType()).isEqualTo(EXPECTED_TYPE);
        assertThat(mCustomView.getParentType()).isEqualTo(EXPECTED_PARENT_TYPE);
    }

    @Test
    public void testAttributes() throws Exception {
        Class<? extends View> viewClass = mCustomView.getViewClass(getClass().getClassLoader());

        for (Attribute attribute : mCustomView.getAttributes()) {
            WrapperUtils.getMethod(viewClass, attribute.getMethodName(), WrapperUtils.getParameters(attribute.getParameters()));
        }
    }
}
