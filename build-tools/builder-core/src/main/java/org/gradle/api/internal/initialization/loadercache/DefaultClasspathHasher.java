package org.gradle.api.internal.initialization.loadercache;

import com.google.common.hash.HashCode;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.internal.classloader.ClasspathHasher;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.execution.fingerprint.FileCollectionFingerprinter;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;

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
