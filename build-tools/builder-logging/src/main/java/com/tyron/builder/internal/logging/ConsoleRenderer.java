package com.tyron.builder.internal.logging;

import com.tyron.builder.internal.UncheckedException;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Renders information in a format suitable for logging to the console.
 */
public class ConsoleRenderer {
    /**
     * Renders a path name as a file URL that is likely recognized by consoles.
     */
    public String asClickableFileUrl(File path) {
        // File.toURI().toString() leads to an URL like this on Mac: file:/reports/index.html
        // This URL is not recognized by the Mac console (too few leading slashes). We solve
        // this be creating an URI with an empty authority.
        try {
            return new URI("file", "", path.toURI().getPath(), null, null).toString();
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }
}
