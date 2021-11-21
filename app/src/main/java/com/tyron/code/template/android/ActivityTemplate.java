package com.tyron.code.template.android;

import android.os.Parcel;

import com.tyron.code.template.CodeTemplate;
import com.tyron.code.template.java.JavaClassTemplate;

public class ActivityTemplate extends JavaClassTemplate {

    public ActivityTemplate() {
        super();
    }

    public ActivityTemplate(Parcel in) {
        super(in);
    }

    @Override
    public String getName() {
        return "Activity";
    }

    @Override
    public void setup() {
        setContents("package " + CodeTemplate.PACKAGE_NAME + ";\n\n" +
                "import android.app.Activity;\n" +
                "import android.os.Bundle;\n\n" +
                "public class " + CodeTemplate.CLASS_NAME + " extends Activity {\n\n" +
                "   @Override\n" +
                "   public void onCreate(Bundle savedInstanceState) {\n" +
                "       super.onCreate(savedInstanceState);\n" +
                "   }\n" +
                "}");
    }
}
