package com.tyron.builder.api.internal.initialization.loadercache;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.internal.classloader.ClasspathHasher;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinter;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;

public class DefaultClasspathHasher implements ClasspathHasher {

    private final FileCollectionFingerprinter fingerprinter;
    private final FileCollectionFactory fileCollectionFactory;

    public DefaultClasspathHasher(FileCollectionFingerprinter fingerprinter, FileCollectionFactory fileCollectionFactory) {
        this.fingerprinter = fingerprinter;
        this.fileCollectionFactory = fileCollectionFactory;
    }

    @Override
    public HashCode hash(ClassPath classpath) {
        CurrentFileCollectionFingerprint fingerprint = fingerprinter.fingerprint(fileCollectionFactory.fixed(classpath.getAsFiles()));
        return fingerprint.getHash();
    }
}
