package com.tyron.code.template.kotlin;

import android.os.Parcel;

import com.tyron.code.template.CodeTemplate;

public class KotlinAbstractClassTemplate extends KotlinClassTemplate {

    public KotlinAbstractClassTemplate() {

    }

    public KotlinAbstractClassTemplate(Parcel in) {
        super(in);
    }

    @Override
    public String getName() {
        return "Kotlin Abstract Class";
    }

    @Override
    public void setup() {
        setContents("package " + CodeTemplate.PACKAGE_NAME + "\n\n" +
                "abstract class " + CodeTemplate.CLASS_NAME + " {\n" +
                "\t" + "\n" +
                "}");
    }
}
