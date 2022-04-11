package com.tyron.builder.api.internal.file.pattern;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegExpPatternStep implements PatternStep {
    private static final String ESCAPE_CHARS = "\\[]^-&.{}()$+|<=!";

    private final Pattern pattern;

    public RegExpPatternStep(String pattern, boolean caseSensitive) {
        this.pattern = Pattern.compile(getRegExPattern(pattern), caseSensitive?0:Pattern.CASE_INSENSITIVE);
    }

    @Override
    public String toString() {
        return "{regexp: " + pattern + "}";
    }

    protected static String getRegExPattern(String pattern) {
        StringBuilder result = new StringBuilder();
        for (int i=0; i<pattern.length(); i++) {
            char next = pattern.charAt(i);
            if (next == '*') {
                result.append(".*");
            } else if (next == '?') {
                result.append(".");
            } else if (ESCAPE_CHARS.indexOf(next) >= 0) {
                result.append('\\');
                result.append(next);
            } else {
                result.append(next);
            }
        }
        return result.toString();
    }

    @Override
    public boolean matches(String testString) {
        Matcher matcher = pattern.matcher(testString);
        return matcher.matches();
    }

}