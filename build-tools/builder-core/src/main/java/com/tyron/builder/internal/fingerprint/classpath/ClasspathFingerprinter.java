package com.tyron.builder.internal.fingerprint.classpath;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinter;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.api.tasks.Classpath;

/**
 * Responsible for calculating a {@link FileCollectionFingerprint} for a {@link FileCollection} representing a Java classpath. Compared to {@link RelativePathFileCollectionFingerprinter} this fingerprinter orders files within any sub-tree.
 *
 * @see Classpath
 */
@ServiceScope(Scopes.UserHome.class)
public interface ClasspathFingerprinter extends FileCollectionFingerprinter {
}

