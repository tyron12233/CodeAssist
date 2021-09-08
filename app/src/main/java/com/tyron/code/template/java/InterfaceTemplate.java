package com.tyron.code.template.java;

import com.tyron.code.template.CodeTemplate;

public class InterfaceTemplate extends CodeTemplate {

    @Override
    public String get() {
        return "package " +
                CodeTemplate.PACKAGE_NAME +
                ";\n" + "\npublic interface " +
                CodeTemplate.CLASS_NAME +
                " {\n\t\n}";
    }

    @Override
    public String getExtension() {
        return ".java";
    }
}