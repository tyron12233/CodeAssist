package org.gradle.api.internal.file;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Transformer;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.internal.UncheckedException;
import org.gradle.util.internal.GFileUtils;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * A minimal resolver, which does not use any native services. Used during bootstrap only. You should generally use {@link FileResolver} instead.
 *
 * TODO - share more stuff with AbstractFileResolver.
 */
public class BasicFileResolver implements Transformer<File, String> {
    private static final Pattern URI_SCHEME = Pattern.compile("[a-zA-Z][a-zA-Z0-9+-\\.]*:.+");
    private final File baseDir;

    public BasicFileResolver(File baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public File transform(String original) {
        if (original.startsWith("file:")) {
            try {
                return GFileUtils.normalize(new File(new URI(original)));
            } catch (URISyntaxException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }

        File file = new File(original);
        if (file.isAbsolute()) {
            return GFileUtils.normalize(file);
        }

        if (URI_SCHEME.matcher(original).matches()) {
            throw new InvalidUserDataException(String.format("Cannot convert URL '%s' to a file.", original));
        }

        file = new File(baseDir, original);
        return GFileUtils.normalize(file);
    }
}