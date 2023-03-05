package org.gradle.groovy.scripts.internal;

import com.google.common.hash.HashCode;
import org.gradle.groovy.scripts.ScriptSource;

public interface ScriptSourceHasher {
    HashCode hash(ScriptSource scriptSource);
}
