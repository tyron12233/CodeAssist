package com.tyron.code.template.kotlin;

import android.os.Parcel;

import com.tyron.code.template.CodeTemplate;

public class KotlinInterfaceTemplate extends KotlinClassTemplate {

    public KotlinInterfaceTemplate() {

    }

    public KotlinInterfaceTemplate(Parcel in) {
        super(in);
    }

    @Override
    public String getName() {
        return "Kotlin Interface";
    }

    @Override
    public void setup() {
        setContents("package " + CodeTemplate.PACKAGE_NAME + "\n\n" +
                "interface " + CodeTemplate.CLASS_NAME + " {\n" +
                "\t" + "\n" +
                "}");
    }
}
