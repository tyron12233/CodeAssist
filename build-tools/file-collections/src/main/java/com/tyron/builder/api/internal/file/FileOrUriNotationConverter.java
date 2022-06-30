package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;
import com.tyron.builder.internal.os.OperatingSystem;
import com.tyron.builder.internal.typeconversion.NotationConvertResult;
import com.tyron.builder.internal.typeconversion.NotationConverter;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.typeconversion.NotationParserBuilder;
import com.tyron.builder.internal.typeconversion.TypeConversionException;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileOrUriNotationConverter implements NotationConverter<Object, Object> {

    private static final Pattern URI_SCHEME = Pattern.compile("([a-zA-Z][a-zA-Z0-9+-\\.]*:).+");
    private static final Pattern ENCODED_URI = Pattern.compile("%([0-9a-fA-F]{2})");

    public FileOrUriNotationConverter() {
    }

    public static NotationParser<Object, Object> parser() {
        return NotationParserBuilder
                .toType(Object.class)
                .typeDisplayName("a File or URI")
                .noImplicitConverters()
                .converter(new FileOrUriNotationConverter())
                .toComposite();
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate("A String or CharSequence path").example("'src/main/java' or '/usr/include'");
        visitor.candidate("A String or CharSequence URI").example("'file:/usr/include'");
        visitor.candidate("A File instance.");
        visitor.candidate("A Path instance.");
        visitor.candidate("A Directory instance.");
        visitor.candidate("A RegularFile instance.");
        visitor.candidate("A URI or URL instance.");
        visitor.candidate("A TextResource instance.");
    }

    @Override
    public void convert(Object notation, NotationConvertResult<? super Object> result) throws TypeConversionException {
        if (notation instanceof File) {
            result.converted(notation);
            return;
        }
        if (notation instanceof Path) {
            result.converted(((Path) notation).toFile());
            return;
        }
        if (notation instanceof FileSystemLocation) {
            result.converted(((FileSystemLocation) notation).getAsFile());
            return;
        }
        if (notation instanceof URL) {
            try {
                notation = ((URL) notation).toURI();
            } catch (URISyntaxException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }
        if (notation instanceof URI) {
            URI uri = (URI) notation;
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                result.converted(new File(uri.getPath()));
            } else {
                result.converted(uri);
            }
            return;
        }
        if (notation instanceof CharSequence) {
            String notationString = notation.toString();
            if (notationString.startsWith("file:")) {
                result.converted(new File(uriDecode(notationString.substring(5))));
                return;
            }
            if (notationString.startsWith("http:") || notationString.startsWith("https:")) {
                convertToUrl(notationString, result);
                return;
            }
            Matcher schemeMatcher = URI_SCHEME.matcher(notationString);
            if (schemeMatcher.matches()) {
                String scheme = schemeMatcher.group(1);
                if (isWindowsRootDirectory(scheme)) {
                    result.converted(new File(notationString));
                    return;
                }
                convertToUrl(notationString, result);
                return;
            }
            result.converted(new File(notationString));
        }
//        if (notation instanceof TextResource) {
//            // TODO: This eagerly resolves a TextResource into a File, ignoring dependencies
//            result.converted(((TextResource) notation).asFile());
//        }
    }

    private boolean isWindowsRootDirectory(String scheme) {
        return scheme.length() == 2 && Character.isLetter(scheme.charAt(0)) && scheme.charAt(1) == ':' && OperatingSystem
                .current().isWindows();
    }

    private void convertToUrl(String notationString, NotationConvertResult<? super Object> result) {
        try {
            result.converted(new URI(notationString));
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    private String uriDecode(String path) {
        StringBuffer builder = new StringBuffer();
        Matcher matcher = ENCODED_URI.matcher(path);
        while (matcher.find()) {
            String val = matcher.group(1);
            matcher.appendReplacement(builder, String.valueOf((char) (Integer.parseInt(val, 16))));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

}
