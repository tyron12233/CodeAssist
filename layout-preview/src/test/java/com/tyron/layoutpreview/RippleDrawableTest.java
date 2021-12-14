package com.tyron.layoutpreview;

import static com.google.common.truth.Truth.assertThat;

import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Value;

import org.junit.Test;

public class RippleDrawableTest extends BaseTest {

    @Test
    public void testRippleDrawableParse() {
        DrawableValue ripple = mInflater.getContext()
                .getProteusResources()
                .getDrawable("ripple_drawable");
        assertThat(ripple).isNotNull();
        assertThat(ripple).isInstanceOf(DrawableValue.RippleValue.class);

        DrawableValue.RippleValue rippleValue = ((DrawableValue.RippleValue) ripple);
        assertThat(rippleValue.color).isNotNull();
        assertThat(rippleValue.mask).isNotNull();
        assertThat(rippleValue.mask).isInstanceOf(DrawableValue.ShapeValue.class);
        
        DrawableValue.ShapeValue mask = (DrawableValue.ShapeValue) rippleValue.mask;
        assertThat(mask.gradient).isNull();
    }
}
