package com.tyron.layoutpreview.drawable;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;

import androidx.test.core.app.ApplicationProvider;

import com.flipkart.android.proteus.ProteusBuilder;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.Array;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;
import com.flipkart.android.proteus.value.VectorDrawable;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class TestVectorDrawable {

    private static final String DRAWABLE = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "    android:width=\"200dp\"\n" +
            "    android:height=\"200dp\"\n" +
            "    android:viewportWidth=\"20\"\n" +
            "    android:viewportHeight=\"20\"\n" +
            "    android:tint=\"@android:color/tertiary_text_light\">\n" +
            "  <path\n" +
            "      android:fillColor=\"@android:color/white\"\n" +
            "      android:pathData=\"M4.5,16.5h8.75c0.41,0 0.75,0.34 0.75,0.75l0,0c0,0.41 -0.34,0.75 -0.75,0.75H4.5C3.67,18 3,17.33 3,16.5V5.75C3,5.34 3.34,5 3.75,5h0C4.16,5 4.5,5.34 4.5,5.75V16.5zM17,3.5v10c0,0.83 -0.67,1.5 -1.5,1.5h-8C6.67,15 6,14.33 6,13.5v-10C6,2.67 6.67,2 7.5,2h8C16.33,2 17,2.67 17,3.5zM15.5,3.5h-8v10h8V3.5z\"/>\n" +
            "</vector>\n";

    private ObjectValue objectValue;
    private ProteusContext context;

    @Before
    public void setup() throws ConvertException, XmlPullParserException, IOException {
        JsonObject jsonObject = new XmlToJsonConverter().convert(DRAWABLE);

        context = new ProteusBuilder().build()
                .createContextBuilder(ApplicationProvider.getApplicationContext())
                .build();
        Value value = new ProteusTypeAdapterFactory(context)
                .VALUE_TYPE_ADAPTER.read(new JsonReader(new StringReader(jsonObject.toString())));

        objectValue = value.getAsObject();
    }

    @Test
    public void test() throws Exception {
        print(objectValue);
    }

    private void print(ObjectValue value) throws Exception {

        VectorDrawable drawable = new VectorDrawable(value, context);
        drawable.setBounds(0, 0, 200, 200);
        drawable.inflate(value);

        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.draw(canvas);

        File file = new File("C:/Users/bounc/AndroidStudioProjects/CodeAssist/LayoutPreview/src/test/java/com/tyron/layoutpreview/drawable/Image.png");
        if (!file.exists()) {
            file.createNewFile();
        }
        FileOutputStream fos = new FileOutputStream(file);
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
    }
}
