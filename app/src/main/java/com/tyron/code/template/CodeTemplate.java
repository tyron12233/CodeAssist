package com.tyron.code.template;

import androidx.annotation.NonNull;

/**
 * Class for creating different templates for classes such as an interface,
 * abstract or regular classes
 */
public abstract class CodeTemplate {

    /**
     * Used to replace the template package name with the app's package name
     */
    public static final String PACKAGE_NAME = "${packageName}";

    public static final String CLASS_NAME = "${className}";

    public abstract String getName();

    /**
     * Used to retrieve the code provided by this template
     * @return template contents
     */
    public abstract String get();

    public abstract String getExtension();

    @NonNull
    @Override
    public String toString() {
        return getName();
    }
}
