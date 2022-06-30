package com.tyron.builder.plugin.management.internal;

import java.util.Iterator;
import java.util.List;

public class MultiPluginRequests implements PluginRequests {

    private final List<PluginRequestInternal> requests;

    public MultiPluginRequests(List<PluginRequestInternal> requests) {
        if (requests.isEmpty()) {
            throw new IllegalStateException("requests must not be empty");
        }
        this.requests = requests;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public Iterator<PluginRequestInternal> iterator() {
        return requests.iterator();
    }

}
