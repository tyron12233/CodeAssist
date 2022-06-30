package com.tyron.builder.internal.resource.local;

/**
 * Can find a locally available candidates for an external resource, through some means.
 *
 * This is different to our caching in that we know very little about locally available resources, other than their
 * binary content. If we can determine the sha1 value of an external resource, we can search the local system to see
 * if a copy can be found (e.g. the local Maven cache).
 *
 * @param <C> The type of the criterion object used to find candidates
 */
public interface LocallyAvailableResourceFinder<C> {

    LocallyAvailableResourceCandidates findCandidates(C criterion);

}
