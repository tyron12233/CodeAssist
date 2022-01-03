package com.tyron.layoutpreview.convert;

import com.flipkart.android.proteus.value.Resource;
import com.google.common.truth.Truth;
import com.tyron.layoutpreview.BaseTest;

import org.junit.Test;

public class TestStringResource extends BaseTest {

    @Test
    public void testStringResource() {
        Resource resource = new Resource("@string/app_name");
        String string = resource.getString(mInflater.getContext());
        Truth.assertThat(string)
                .isEqualTo("TEST");
    }
}
