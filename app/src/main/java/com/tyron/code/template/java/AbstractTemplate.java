package com.tyron.code.template.java;

import android.os.Parcel;

import com.tyron.code.template.CodeTemplate;

public class AbstractTemplate extends JavaClassTemplate {

    public AbstractTemplate() {

    }

    public AbstractTemplate(Parcel in) {
        super(in);
    }

    @Override
    public String getName() {
        return "Abstract";
    }

    @Override
    public void setup() {
        setContents("package " +
                CodeTemplate.PACKAGE_NAME +
                ";\n" +
                "\npublic abstract" +
                " class " +
                CodeTemplate.CLASS_NAME +
                " {\n\t\n}");
    }
}