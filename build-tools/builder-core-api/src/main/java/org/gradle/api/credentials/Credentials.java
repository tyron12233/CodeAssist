package org.gradle.api.credentials;

import org.gradle.api.NonExtensible;

/**
 * Base interface for credentials used for different authentication purposes.
 * (e.g authenticated {@link org.gradle.api.artifacts.dsl.RepositoryHandler})
 * */
@NonExtensible
public interface Credentials {
}
