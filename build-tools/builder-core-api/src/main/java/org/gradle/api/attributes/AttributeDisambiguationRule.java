package org.gradle.api.attributes;

import org.gradle.api.Action;

/**
 * A rule that selects the best value out of a set of two or more candidates.
 *
 * @since 4.0
 * @param <T> The attribute value type.
 */
public interface AttributeDisambiguationRule<T> extends Action<MultipleCandidatesDetails<T>> {
}
