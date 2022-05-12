package com.tyron.builder.api.internal.resources;

import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.resources.StringBackedTextResource;
import com.tyron.builder.api.resources.TextResource;
import com.tyron.builder.api.resources.TextResourceFactory;

import java.net.URI;
import java.nio.charset.Charset;

public class DefaultTextResourceFactory implements TextResourceFactory {
    private final FileOperations fileOperations;
    private final TemporaryFileProvider tempFileProvider;
    private ApiTextResourceAdapter.Factory apiTextResourcesAdapterFactory;

    public DefaultTextResourceFactory(FileOperations fileOperations, TemporaryFileProvider tempFileProvider, ApiTextResourceAdapter.Factory apiTextResourcesAdapterFactory) {
        this.fileOperations = fileOperations;
        this.tempFileProvider = tempFileProvider;
        this.apiTextResourcesAdapterFactory = apiTextResourcesAdapterFactory;
    }

    @Override
    public TextResource fromString(String string) {
        return new StringBackedTextResource(tempFileProvider, string);
    }

    @Override
    public TextResource fromFile(Object file, String charset) {
        return new FileCollectionBackedTextResource(tempFileProvider, fileOperations.immutableFiles(file), Charset.forName(charset));
    }

    @Override
    public TextResource fromFile(Object file) {
        return fromFile(file, Charset.defaultCharset().name());
    }

    @Override
    public TextResource fromArchiveEntry(Object archive, String entryPath, String charset) {
        return new FileCollectionBackedArchiveTextResource(fileOperations, tempFileProvider, fileOperations.immutableFiles(archive), entryPath, Charset.forName(charset));
    }

    @Override
    public TextResource fromArchiveEntry(Object archive, String entryPath) {
        return fromArchiveEntry(archive, entryPath, Charset.defaultCharset().name());
    }

    @Override
    public TextResource fromUri(Object uri) {
        return fromUri(uri, false);
    }

    @Override
    public TextResource fromInsecureUri(Object uri) {
        return fromUri(uri, true);
    }

    private TextResource fromUri(Object uri, boolean allowInsecureProtocol) {
        URI rootUri = fileOperations.uri(uri);

//        HttpRedirectVerifier redirectVerifier =
//            HttpRedirectVerifierFactory.create(
//                rootUri,
//                allowInsecureProtocol,
//                () -> throwExceptionDueToInsecureProtocol(rootUri),
//                redirect -> throwExceptionDueToInsecureRedirect(uri, redirect)
//            );
//        return apiTextResourcesAdapterFactory.create(rootUri, redirectVerifier);
        throw new UnsupportedOperationException();
    }

    private void throwExceptionDueToInsecureProtocol(URI rootUri) {
//        String contextualAdvice =
//            String.format("The provided URI '%s' uses an insecure protocol (HTTP). ", rootUri);
//        String switchToAdvice =
//            String.format(
//                "Switch the URI to '%s' or try 'resources.text.fromInsecureUri(\"%s\")' to silence the warning. ",
//                GUtil.toSecureUrl(rootUri),
//                rootUri
//            );
//        String dslMessage =
//            Documentation
//                .dslReference(TextResourceFactory.class, "fromInsecureUri(java.lang.Object)")
//                .consultDocumentationMessage();
//
//        String message =
//            "Loading a TextResource from an insecure URI, without explicit opt-in, is unsupported. " +
//                contextualAdvice +
//                switchToAdvice +
//                dslMessage;
//        throw new InvalidUserCodeException(message);
    }

    private void throwExceptionDueToInsecureRedirect(Object uri, URI redirect) throws InvalidUserCodeException {
//        String contextualAdvice =
//            String.format("'%s' redirects to insecure '%s'. ", uri, redirect);
//        String switchToAdvice =
//            "Switch to HTTPS or use TextResourceFactory.fromInsecureUri(Object) to silence the warning. ";
//        String dslMessage =
//            Documentation
//                .dslReference(TextResourceFactory.class, "fromInsecureUri(java.lang.Object)")
//                .consultDocumentationMessage();
//        String message =
//            "Loading a TextResource from an insecure redirect, without explicit opt-in, is unsupported. " +
//                contextualAdvice +
//                switchToAdvice +
//                dslMessage;
//        throw new InvalidUserCodeException(message);
    }
}
