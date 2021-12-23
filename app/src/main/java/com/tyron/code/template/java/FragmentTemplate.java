package com.tyron.code.template.java;

import android.os.Parcel;

import com.tyron.code.template.CodeTemplate;

public class FragmentTemplate extends CodeTemplate {

    public FragmentTemplate() {

    }

    public FragmentTemplate(Parcel in) {
        super(in);
    }

    @Override
    public String getName() {
        return "Fragment class";
    }

    @Override
    public void setup() {
        setContents("package " +
                CodeTemplate.PACKAGE_NAME +
                ";\n\nimport androidx.fragment.app.Fragment;" + "\npublic class " +
                CodeTemplate.CLASS_NAME +
                " extends Fragment {\n\t\n}");
    }

    @Override
    public String getExtension() {
        return ".java";
    }
}
