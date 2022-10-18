package org.gradle.internal.fingerprint.classpath;

import org.gradle.api.file.FileCollection;
import org.gradle.internal.execution.fingerprint.FileCollectionFingerprinter;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.api.tasks.Classpath;

/**
 * Responsible for calculating a {@link FileCollectionFingerprint} for a {@link FileCollection} representing a Java classpath. Compared to {@link RelativePathFileCollectionFingerprinter} this fingerprinter orders files within any sub-tree.
 *
 * @see Classpath
 */
@ServiceScope(Scopes.UserHome.class)
public interface ClasspathFingerprinter extends FileCollectionFingerprinter {
}

