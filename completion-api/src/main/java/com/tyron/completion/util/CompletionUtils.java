package com.tyron.completion.util;

import com.tyron.editor.CharPosition;

import java.util.function.Predicate;

public class CompletionUtils {

    public static final Predicate<Character> JAVA_PREDICATE = it ->
            Character.isJavaIdentifierPart(it) || it == '.';

    public static String computePrefix(CharSequence line, CharPosition position, Predicate<Character> predicate) {
        int begin = position.getColumn();
        for (;begin > 0;begin--) {
            if (!predicate.test(line.charAt(begin - 1))) {
                break;
            }
        }
        return String.valueOf(line.subSequence(begin, position.getColumn()));
    }
}
