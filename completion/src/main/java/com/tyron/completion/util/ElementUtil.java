package com.tyron.completion.util;

import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;

import java.util.Set;

public class ElementUtil {

    public static boolean isFinal(ExecutableElement element) {
        Set<Modifier> modifiers = element.getModifiers();
        if (modifiers == null) {
            return false;
        }
        return modifiers.contains(Modifier.FINAL);
    }
}
