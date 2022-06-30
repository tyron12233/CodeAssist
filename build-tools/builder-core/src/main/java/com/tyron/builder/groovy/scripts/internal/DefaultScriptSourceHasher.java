package com.tyron.builder.groovy.scripts.internal;

import com.google.common.hash.HashCode;
import com.tyron.builder.groovy.scripts.ScriptSource;

public class DefaultScriptSourceHasher implements ScriptSourceHasher {

    @Override
    public HashCode hash(ScriptSource scriptSource) {
        return scriptSource.getResource().getContentHash();
    }
}
