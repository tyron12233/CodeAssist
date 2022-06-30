package com.tyron.builder.internal.resource.local;


import com.google.common.hash.HashCode;

/**
 * A set of locally available resources that were “selected” through some means.
 */
public interface LocallyAvailableResourceCandidates {

    boolean isNone();

    LocallyAvailableResource findByHashValue(HashCode hashValue);

}
