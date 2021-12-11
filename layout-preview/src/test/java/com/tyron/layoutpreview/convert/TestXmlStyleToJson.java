package com.tyron.layoutpreview.convert;

import com.google.common.truth.Truth;
import com.google.gson.JsonObject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class TestXmlStyleToJson {

    private static final String TEST_DATA = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<resources>\n" +
            "    <style name=\"AppTheme\" parent=\"Theme.MaterialComponents.NoActionBar\">\n" +
            "        <item name=\"colorAccent\">@color/colorAccent</item>\n" +
            "        <item name=\"colorPrimary\">@color/colorPrimary</item>\n" +
            "        <item name=\"colorSecondary\">@color/colorSecondary</item>\n" +
            "        <item name=\"colorOnSecondary\">@color/colorOnSecondary</item>\n" +
            "        <item name=\"android:textColorPrimary\">@color/white</item>\n" +
            "        <item name=\"android:statusBarColor\">@color/colorBackground</item>\n" +
            "        <item name=\"colorControlNormal\">@color/colorControlNormal</item>\n" +
            "        <item name=\"actionModeBackground\">@android:color/transparent</item>\n" +
            "    </style>\n" +
            "\n" +
            "    <style name=\"HorizontalDivider\">\n" +
            "        <item name=\"android:layout_width\">match_parent</item>\n" +
            "        <item name=\"android:layout_height\">1dp</item>\n" +
            "        <item name=\"android:background\">?android:attr/listDivider</item>\n" +
            "    </style>\n" +
            "\n" +
            "    <style name=\"VerticalDivider\">\n" +
            "        <item name=\"android:layout_width\">0.8dp</item>\n" +
            "        <item name=\"android:layout_height\">wrap_content</item>\n" +
            "        <item name=\"android:background\">?android:attr/listDivider</item>\n" +
            "    </style>\n" +
            "\n" +
            "    <style name=\"TextButton.IconOnly\" parent=\"Widget.MaterialComponents.Button.TextButton\">\n" +
            "        <item name=\"iconPadding\">0dp</item>\n" +
            "        <item name=\"cornerFamily\">cut</item>\n" +
            "        <item name=\"android:insetTop\">0dp</item>\n" +
            "        <item name=\"android:insetBottom\">0dp</item>\n" +
            "        <item name=\"android:paddingLeft\">12dp</item>\n" +
            "        <item name=\"android:paddingRight\">12dp</item>\n" +
            "        <item name=\"android:paddingTop\">8dp</item>\n" +
            "        <item name=\"android:paddingBottom\">8dp</item>\n" +
            "        <item name=\"android:minWidth\">48dp</item>\n" +
            "        <item name=\"android:minHeight\">48dp</item>\n" +
            "    </style>\n" +
            "\n" +
            "    <style name=\"OutlinedButton.IconOnly\" parent=\"Widget.MaterialComponents.Button.OutlinedButton\">\n" +
            "        <item name=\"iconPadding\">0dp</item>\n" +
            "        <item name=\"android:insetTop\">0dp</item>\n" +
            "        <item name=\"android:insetBottom\">0dp</item>\n" +
            "        <item name=\"android:paddingLeft\">12dp</item>\n" +
            "        <item name=\"android:paddingRight\">12dp</item>\n" +
            "        <item name=\"android:minWidth\">48dp</item>\n" +
            "        <item name=\"android:minHeight\">48dp</item>\n" +
            "    </style>\n" +
            "\n" +
            "    <style name=\"RoundOutlinedButton.IconOnly\" parent=\"\">\n" +
            "        <item name=\"cornerFamily\">rounded</item>\n" +
            "        <item name=\"cornerSize\">50%</item>\n" +
            "        <item name=\"android:maxWidth\">48dp</item>\n" +
            "        <item name=\"android:maxHeight\">48dp</item>\n" +
            "    </style>\n" +
            "\n" +
            "    <style name=\"ButtonBar.IconOnly.Left\" parent=\"\">\n" +
            "        <item name=\"cornerFamily\">rounded</item>\n" +
            "        <item name=\"cornerSizeTopLeft\">8dp</item>\n" +
            "        <item name=\"cornerSizeTopRight\">0dp</item>\n" +
            "        <item name=\"cornerSizeBottomLeft\">8dp</item>\n" +
            "        <item name=\"cornerSizeBottomRight\">0dp</item>\n" +
            "    </style>\n" +
            "\n" +
            "    <style name=\"ButtonBar.IconOnly.Middle\" parent=\"\">\n" +
            "        <item name=\"cornerFamily\">rounded</item>\n" +
            "        <item name=\"cornerSize\">0dp</item>\n" +
            "    </style>\n" +
            "\n" +
            "    <style name=\"ButtonBar.IconOnly.Right\" parent=\"\">\n" +
            "        <item name=\"cornerFamily\">rounded</item>\n" +
            "        <item name=\"cornerSizeTopLeft\">0dp</item>\n" +
            "        <item name=\"cornerSizeTopRight\">8dp</item>\n" +
            "        <item name=\"cornerSizeBottomLeft\">0dp</item>\n" +
            "        <item name=\"cornerSizeBottomRight\">8dp</item>\n" +
            "    </style>\n" +
            "\n" +
            "    <style name=\"TabLayoutText\" parent=\"TextAppearance.Design.Tab\">\n" +
            "        <item name=\"textAllCaps\">false</item>\n" +
            "        <item name=\"android:textAllCaps\">false</item>\n" +
            "    </style>\n" +
            "</resources>\n";

    @Test
    public void testConvertStyleToJson() throws ConvertException, XmlPullParserException, IOException {
        XmlToJsonConverter converter = new XmlToJsonConverter();
        JsonObject converted = converter.convert(TEST_DATA);
        Truth.assertThat(converted)
                .isNotNull();
    }
}
