package com.tyron.code.template.java;

import com.tyron.code.template.CodeTemplate;

public class JavaClassTemplate extends CodeTemplate {

    @Override
    public String getName() {
        return "Java class";
    }

    @Override
    public String get() {
        return "package " +
                CodeTemplate.PACKAGE_NAME +
                ";\n" + "\npublic class " +
                CodeTemplate.CLASS_NAME +
                " {\n\t\n}";
    }

    @Override
    public String getExtension() {
        return ".java";
    }
}
