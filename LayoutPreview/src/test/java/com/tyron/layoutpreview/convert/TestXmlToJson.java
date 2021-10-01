package com.tyron.layoutpreview.convert;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonObject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TestXmlToJson {

    private static final String TEST_LAYOUT = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<androidx.constraintlayout.widget.ConstraintLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "    xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n" +
            "    xmlns:tools=\"http://schemas.android.com/tools\"\n" +
            "    android:layout_width=\"match_parent\"\n" +
            "    android:layout_height=\"match_parent\">\n" +
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

    private static final String EXPECTED_RESULT = "{\"type\":\"androidx.constraintlayout.widget.ConstraintLayout\",\"xmlns:app\":\"http://schemas.android.com/apk/res-auto\",\"android:layout_width\":\"match_parent\",\"android:layout_height\":\"match_parent\",\"children\":[{\"type\":\"com.google.android.material.textfield.TextInputLayout\",\"android:id\":\"til_class_name\",\"android:layout_width\":\"0dp\",\"android:layout_height\":\"wrap_content\",\"android:layout_marginStart\":\"16dp\",\"android:layout_marginTop\":\"16dp\",\"android:layout_marginEnd\":\"16dp\",\"app:layout_constraintEnd_toEndOf\":\"parent\",\"app:layout_constraintStart_toStartOf\":\"parent\",\"app:layout_constraintTop_toTopOf\":\"parent\",\"children\":[{\"type\":\"com.google.android.material.textfield.TextInputEditText\",\"android:id\":\"et_class_name\",\"android:layout_width\":\"match_parent\",\"android:layout_height\":\"wrap_content\",\"android:hint\":\"@string/create_class_dialog_class_name\"}]},{\"type\":\"com.google.android.material.textfield.TextInputLayout\",\"android:layout_width\":\"0dp\",\"android:layout_height\":\"wrap_content\",\"android:layout_marginStart\":\"16dp\",\"android:layout_marginTop\":\"8dp\",\"android:layout_marginEnd\":\"16dp\",\"android:layout_marginBottom\":\"16dp\",\"app:layout_constraintBottom_toBottomOf\":\"parent\",\"app:layout_constraintEnd_toEndOf\":\"parent\",\"app:layout_constraintStart_toStartOf\":\"parent\",\"app:layout_constraintTop_toBottomOf\":\"til_class_name\",\"app:layout_constraintVertical_bias\":\"0.0\",\"children\":[{\"type\":\"com.google.android.material.textfield.MaterialAutoCompleteTextView\",\"android:id\":\"et_class_type\",\"android:layout_width\":\"match_parent\",\"android:layout_height\":\"match_parent\",\"android:hint\":\"@string/create_class_dialog_class_type\",\"android:inputType\":\"none\"}]}]}";

    @Test
    public void testConvert() throws Exception {
        JsonObject object = new XmlToJsonConverter().convert(TEST_LAYOUT);

        assertThat(object.toString())
                .isEqualTo(EXPECTED_RESULT);
    }
}
