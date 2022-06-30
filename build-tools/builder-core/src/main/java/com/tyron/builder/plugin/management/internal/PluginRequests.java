package com.tyron.builder.plugin.management.internal;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public interface PluginRequests extends Iterable<PluginRequestInternal> {

    PluginRequests EMPTY = new EmptyPluginRequests();

    boolean isEmpty();

    class EmptyPluginRequests implements PluginRequests {

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public PluginRequests mergeWith(PluginRequests requests) {
            return requests;
        }

        @Override
        public Iterator<PluginRequestInternal> iterator() {
            return Collections.emptyIterator();
        }
    }

    default PluginRequests mergeWith(PluginRequests requests) {
        if (isEmpty()) {
            return requests;
        } else if (requests.isEmpty()) {
            return this;
        } else {
            return new MergedPluginRequests(this, requests);
        }
    }

    static PluginRequests of(PluginRequestInternal request) {
        return new SingletonPluginRequests(request);
    }

    static PluginRequests of(List<PluginRequestInternal> list) {
        if (list.isEmpty()) {
            return EMPTY;
        } else if (list.size() == 1) {
            return new SingletonPluginRequests(list.get(0));
        } else {
            return new MultiPluginRequests(list);
        }
    }

}
