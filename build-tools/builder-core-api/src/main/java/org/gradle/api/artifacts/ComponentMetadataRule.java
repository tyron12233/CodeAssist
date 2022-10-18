package org.gradle.api.artifacts;

import org.gradle.api.Action;

/**
 * A rule that modify {@link ComponentMetadataDetails component metadata}.
 *
 * @since 4.9
 */
public interface ComponentMetadataRule extends Action<ComponentMetadataContext> {
}
