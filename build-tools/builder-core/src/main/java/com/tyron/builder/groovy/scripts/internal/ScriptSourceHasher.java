package com.tyron.builder.groovy.scripts.internal;

import com.google.common.hash.HashCode;
import com.tyron.builder.groovy.scripts.ScriptSource;

public interface ScriptSourceHasher {
    HashCode hash(ScriptSource scriptSource);
}
