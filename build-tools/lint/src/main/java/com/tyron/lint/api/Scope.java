package com.tyron.lint.api;

import java.util.EnumSet;

public enum Scope {

    /**
     * The analysis only considers a single Java source file at a time.
     * <p>
     * Issues which are only affected by a single Java source file can be
     * checked for incrementally when a Java source file is edited.
     */
    JAVA_FILE,

    ALL;

    /** Scope-set used for detectors which are affected by a single Java source file */
    public static final EnumSet<Scope> JAVA_FILE_SCOPE = EnumSet.of(JAVA_FILE);
}
