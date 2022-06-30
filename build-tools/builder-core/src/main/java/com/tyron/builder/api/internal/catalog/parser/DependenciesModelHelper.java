package com.tyron.builder.api.internal.catalog.parser;

import java.util.regex.Pattern;

public abstract class DependenciesModelHelper {
    public final static String ALIAS_REGEX = "[a-z]([a-zA-Z0-9_.\\-])+";
    public final static Pattern ALIAS_PATTERN = Pattern.compile(ALIAS_REGEX);
}
