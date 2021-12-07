package com.tyron.layoutpreview.convert;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import com.flipkart.android.proteus.Proteus;
import com.flipkart.android.proteus.ProteusBuilder;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusLayoutInflater;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;
import com.google.common.truth.Truth;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;

import org.codehaus.plexus.util.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.StringReader;

@RunWith(RobolectricTestRunner.class)
public class TestLayoutToXml {

    private static final String TEST_LAYOUT =
            "<androidx.constraintlayout.widget.ConstraintLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "    xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n" +
            "    xmlns:tools=\"htt" +
                    "p://schemas.android.com/tools\"\n" +
            "    android:layout_width=\"match_parent\"\n" +
                    "    android:layout_height=\"match_parent\">\n" +
                    "    \n" +
                    "    <android.widget.LinearLayout\n" +
                    "        android:id=\"@+id/regularAndroidView\"\n" +
                    "        android:layout_width=\"wrap_content\"\n" +
                    "        android:layout_height=\"wrap_content\">\n" +
                    "        \n" +
                    "        <android.view.View\n" +
                    "            android:id=\"@+id/androidView\"\n" +
                    "            android:layout_width=\"match_parent\"\n" +
                    "            android:layout_height=\"match_parent\" />\n" +
                    "    " +
                    "</android.widget.LinearLayout>\n" +
                    "\n" +
                    "    <com.google.android.material.textfield.TextInputLayout\n" +
                    "        android:id=\"@+id/til_class_name\"\n" +
            "        style=\"@style/Widget.MaterialComponents.TextInputLayout.FilledBox.Dense\"\n" +
            "        android:layout_width=\"0dp\"\n" +
            "        android:layout_height=\"wrap_content\"\n" +
            "        android:layout_marginStart=\"16dp\"\n" +
            "        android:layout_marginTop=\"16dp\"\n" +
            "        android:layout_marginEnd=\"16dp\"\n" +
            "        app:layout_constraintEnd_toEndOf=\"parent\"\n" +
            "        app:layout_constraintStart_toStartOf=\"parent\"\n" +
            "        app:layout_constraintTop_toTopOf=\"parent\">\n" +
            "\n" +
            "        <com.google.android.material.textfield.TextInputEditText\n" +
            "            android:id=\"@+id/et_class_name\"\n" +
            "            android:layout_width=\"match_parent\"\n" +
            "            android:layout_height=\"wrap_content\"\n" +
            "            android:hint=\"@string/create_class_dialog_class_name\" />\n" +
            "    </com.google.android.material.textfield.TextInputLayout>\n" +
            "\n" +
            "    <com.google.android.material.textfield.TextInputLayout\n" +
            "        style=\"@style/Widget.MaterialComponents.TextInputLayout.FilledBox.Dense.ExposedDropdownMenu\"\n" +
            "        android:layout_width=\"0dp\"\n" +
            "        android:layout_height=\"wrap_content\"\n" +
            "        android:layout_marginStart=\"16dp\"\n" +
            "        android:layout_marginTop=\"8dp\"\n" +
            "        android:layout_marginEnd=\"16dp\"\n" +
            "        android:layout_marginBottom=\"16dp\"\n" +
            "        app:layout_constraintBottom_toBottomOf=\"parent\"\n" +
            "        app:layout_constraintEnd_toEndOf=\"parent\"\n" +
            "        app:layout_constraintStart_toStartOf=\"parent\"\n" +
            "        app:layout_constraintTop_toBottomOf=\"@+id/til_class_name\"\n" +
            "        app:layout_constraintVertical_bias=\"0.0\">\n" +
            "\n" +
            "        <com.google.android.material.textfield.MaterialAutoCompleteTextView\n" +
            "            android:id=\"@+id/et_class_type\"\n" +
            "            android:layout_width=\"match_parent\"\n" +
            "            android:layout_height=\"match_parent\"\n" +
            "            android:hint=\"@string/create_class_dialog_class_type\"\n" +
            "            android:inputType=\"none\"/>\n" +
            "    </com.google.android.material.textfield.TextInputLayout>\n" +
            "</androidx.constraintlayout.widget.ConstraintLayout>";

    private final ProteusLayoutInflater.Callback mCallback = new ProteusLayoutInflater.Callback() {
        @NonNull
        @Override
        public ProteusView onUnknownViewType(ProteusContext context, ViewGroup parent, String type, Layout layout, ObjectValue data, int index) {
            ProteusView proteusView = new ProteusView() {
                @Override
                public Manager getViewManager() {
                    return null;
                }

                @Override
                public void setViewManager(@NonNull Manager manager) {

                }

                @NonNull
                @Override
                public View getAsView() {
                    return null;
                }
            };
            return proteusView;
        }

        @Override
        public void onEvent(String event, Value value, ProteusView view) {

        }
    };

    @Test
    public void test() throws Exception {
        JsonObject object = new XmlToJsonConverter().convert(TEST_LAYOUT);

        ProteusBuilder builder = new ProteusBuilder();
        Proteus proteus = builder.build();
        ProteusContext context = proteus.createContextBuilder(ApplicationProvider.getApplicationContext())
                .setCallback(mCallback)
                .build();
        ProteusTypeAdapterFactory.PROTEUS_INSTANCE_HOLDER.setProteus(proteus);

        Value read = new ProteusTypeAdapterFactory(context).VALUE_TYPE_ADAPTER.read(new JsonReader(
                new StringReader(object.toString())
        ));

        Layout layout = read.getAsLayout();

        LayoutToXmlConverter converter = new LayoutToXmlConverter(context);
        String convert = converter.convert(layout);

        String expected = XmlPrettyPrinter.prettyPrint(TEST_LAYOUT, XmlFormatPreferences.defaults(),
                XmlFormatStyle.LAYOUT, "\n");
        String result = XmlPrettyPrinter.prettyPrint(convert, XmlFormatPreferences.defaults(),
                XmlFormatStyle.LAYOUT, "\n");

        Truth.assertThat(result)
                .isEqualTo(expected);
    }
}
