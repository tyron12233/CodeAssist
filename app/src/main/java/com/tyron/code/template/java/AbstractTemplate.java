package com.tyron.code.template.java;

import com.tyron.code.template.CodeTemplate;

public class AbstractTemplate extends CodeTemplate {

    @Override
    public String getName() {
        return "Abstract";
    }

    @Override
    public String get() {
        return "package " +
                CodeTemplate.PACKAGE_NAME +
                ";\n" +
                "\npublic abstract" +
                " class " +
                CodeTemplate.CLASS_NAME +
                " {\n\t\n}";
    }

    @Override
    public String getExtension() {
        return ".java";
    }
}