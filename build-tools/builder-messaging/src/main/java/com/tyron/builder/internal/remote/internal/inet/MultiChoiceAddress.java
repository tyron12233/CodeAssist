package com.tyron.builder.internal.remote.internal.inet;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MultiChoiceAddress implements InetEndpoint {
    private final UUID canonicalAddress;
    private final int port;
    private final List<InetAddress> candidates;

    public MultiChoiceAddress(UUID canonicalAddress, int port, List<InetAddress> candidates) {
        this.canonicalAddress = canonicalAddress;
        this.port = port;
        this.candidates = new ArrayList<InetAddress>(candidates);
    }

    @Override
    public String getDisplayName() {
        return "[" + canonicalAddress + " port:" + port + ", addresses:" + candidates + "]";
    }

    public UUID getCanonicalAddress() {
        return canonicalAddress;
    }

    @Override
    public List<InetAddress> getCandidates() {
        return candidates;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MultiChoiceAddress other = (MultiChoiceAddress) o;
        return other.canonicalAddress.equals(canonicalAddress) && port == other.port && candidates.equals(other.candidates);
    }

    @Override
    public int hashCode() {
        return canonicalAddress.hashCode();
    }

    public MultiChoiceAddress addAddresses(Iterable<InetAddress> candidates) {
        return new MultiChoiceAddress(canonicalAddress, port, Lists.newArrayList(Iterables.concat(candidates, this.candidates)));
    }
}
