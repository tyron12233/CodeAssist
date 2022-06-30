package com.tyron.viewbinding.tool.util;

public class ParserHelper {
    public static String toClassName(String name) {
        StringBuilder builder = new StringBuilder();
        for (String item : name.split("[_-]")) {
            builder.append(StringUtils.capitalize(item));
        }
        return builder.toString();
    }

    public static String stripExtension(String name) {
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}