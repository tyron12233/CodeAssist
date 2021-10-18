package com.tyron.code.template.xml;

import android.os.Parcel;

import com.tyron.code.template.CodeTemplate;

public class LayoutTemplate extends CodeTemplate {
    public LayoutTemplate() {

    }

    public LayoutTemplate(Parcel in) {
        super(in);
    }

    @Override
    public String getName() {
        return "Layout XML";
    }

    @Override
    public void setup() {
        setContents("<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"match_parent\"\n" +
                "    android:orientation=\"vertical\">\n" +
                "\n" +
                "</LinearLayout>");
    }

    @Override
    public String getExtension() {
        return ".xml";
    }
}
