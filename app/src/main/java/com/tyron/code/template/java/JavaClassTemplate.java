package com.tyron.code.template.java;

import android.os.Parcel;

import com.tyron.code.template.CodeTemplate;

public class JavaClassTemplate extends CodeTemplate {

    public JavaClassTemplate() {

    }

    public JavaClassTemplate(Parcel in) {
        super(in);
    }

    @Override
    public String getName() {
        return "Java class";
    }

    @Override
    public void setup() {
        setContents("package " +
                CodeTemplate.PACKAGE_NAME +
                ";\n" + "\npublic class " +
                CodeTemplate.CLASS_NAME +
                " {\n\t\n}");
    }

    @Override
    public String getExtension() {
        return ".java";
    }
}
