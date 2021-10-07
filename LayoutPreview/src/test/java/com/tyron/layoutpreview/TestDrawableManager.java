package com.tyron.layoutpreview;

import androidx.test.core.app.ApplicationProvider;

import com.flipkart.android.proteus.Proteus;
import com.flipkart.android.proteus.ProteusBuilder;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Value;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TestDrawableManager {

    private static final String TEST_DRAWABLE = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "    android:width=\"20dp\"\n" +
            "    android:height=\"20dp\"\n" +
            "    android:viewportWidth=\"20\"\n" +
            "    android:viewportHeight=\"20\"\n" +
            "    android:tint=\"?attr/colorControlNormal\">\n" +
            "  <path\n" +
            "      android:fillColor=\"@android:color/white\"\n" +
            "      android:pathData=\"M10,6L8.44,4.44C8.16,4.16 7.78,4 7.38,4H3.5C2.67,4 2,4.67 2,5.5v9C2,15.33 2.67,16 3.5,16h13c0.83,0 1.5,-0.67 1.5,-1.5v-7C18,6.67 17.33,6 16.5,6H10z\"/>\n" +
            "</vector>\n";

    private ProteusContext mContext;

    @Before
    public void setup() {
        mContext = new ProteusBuilder().build()
                .createContextBuilder(ApplicationProvider.getApplicationContext())
                .build();
    }
    @Test
    public void test() throws Exception {
        XmlToJsonConverter converter = new XmlToJsonConverter();
        JsonObject object = converter.convert(TEST_DRAWABLE);
        ProteusTypeAdapterFactory factory = new ProteusTypeAdapterFactory(mContext);
        Value value = factory.VALUE_TYPE_ADAPTER.fromJson(object.toString());
        DrawableValue drawableValue = DrawableValue.valueOf(value.getAsObject(), mContext);
        System.out.println(object.toString());
    }
}
