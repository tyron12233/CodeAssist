package com.tyron.code.template.kotlin;

import android.os.Parcel;

import com.tyron.code.template.CodeTemplate;

public class KotlinClassTemplate extends CodeTemplate {

    public KotlinClassTemplate() {

    }

    public KotlinClassTemplate(Parcel in) {
        super(in);
    }

    @Override
    public String getName() {
        return "Kotlin class";
    }

    @Override
    public void setup() {
        setContents("package " + CodeTemplate.PACKAGE_NAME + "\n\n" +
                "class " + CodeTemplate.CLASS_NAME + " {\n" +
                "\t" + "\n" +
                "}");
    }

    @Override
    public String getExtension() {
        return ".kt";
    }
}