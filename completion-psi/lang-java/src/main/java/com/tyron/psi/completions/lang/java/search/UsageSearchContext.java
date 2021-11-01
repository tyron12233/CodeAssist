package com.tyron.psi.completions.lang.java.search;

public interface UsageSearchContext {
    /**
     * Element's usages in its language code are requested
     */
    short IN_CODE        = 0x1;

    /**
     * Usages in comments are requested
     */
    short IN_COMMENTS    = 0x2;

    /**
     * Usages in string literals are requested
     */
    short IN_STRINGS     = 0x4;

    /**
     * Element's usages in other languages are requested,
     * e.g. usages of java class in jsp attribute value
     */
    short IN_FOREIGN_LANGUAGES = 0x8;

    /**
     * Plain text occurrences are requested
     */
    short IN_PLAIN_TEXT  = 0x10;

    /**
     * Any of above
     */
    short ANY            = 0xFF;
}
