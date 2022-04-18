package com.tyron.builder.api.file;

import com.tyron.builder.api.providers.Property;

public interface ExpandDetails {
    /**
     * Controls if the underlying {@link groovy.text.SimpleTemplateEngine} escapes backslashes in the file before processing. If this is set to {@code false} then escape sequences in the processed
     * files ({@code \n}, {@code \t}, {@code \\}, etc) are converted to the symbols that they represent, so, for example {@code \n} becomes newline. If set to {@code true} then escape sequences are
     * left as is.
     * <p>
     * Default value is {@code false}.
     *
     * @see groovy.text.SimpleTemplateEngine#setEscapeBackslash(boolean)
     */
    Property<Boolean> getEscapeBackslash();
}
