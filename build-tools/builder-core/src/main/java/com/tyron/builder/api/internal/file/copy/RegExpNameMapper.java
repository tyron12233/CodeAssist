package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.Transformer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegExpNameMapper implements Transformer<String, String> {
    private final Pattern pattern;
    private transient Matcher matcher;
    private final String replacement;

    public RegExpNameMapper(String sourceRegEx, String replaceWith) {
        this(Pattern.compile(sourceRegEx), replaceWith);
    }

    public RegExpNameMapper(Pattern sourceRegEx, String replaceWith) {
        pattern = sourceRegEx;
        replacement = replaceWith;
    }

    @Override
    public String transform(String source) {
        if (matcher == null) {
            matcher = pattern.matcher(source);
        } else {
            matcher.reset(source);
        }
        String result = source;
        if (matcher.find()) {
            result = matcher.replaceFirst(replacement);
        }
        return result;
    }
}
