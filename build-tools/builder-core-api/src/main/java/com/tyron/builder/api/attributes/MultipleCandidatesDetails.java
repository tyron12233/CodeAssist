package com.tyron.builder.api.attributes;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Provides context about candidates for an attribute. In particular, this class gives access to
 * the list of candidates on the producer side.
 *
 * @param <T> the concrete type of the attribute
 * @since 3.3
 */
public interface MultipleCandidatesDetails<T> {
    /**
     * Returns the value of the attribute specified by the consumer.
     *
     * @return The value or {@code null} if the consumer did not specify a value.
     * @since 4.1
     */
    @Nullable
    T getConsumerValue();

    /**
     * A set of candidate values.
     *
     * @return the set of candidates
     */
    Set<T> getCandidateValues();

    /**
     * Calling this method indicates that the candidate is the closest match. It is allowed to call this method several times with
     * different values, in which case it indicates that multiple candidates are equally compatible.
     *
     * @param candidate the closest match
     */
    void closestMatch(T candidate);

}
