package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.groovy.scripts.DelegatingScriptSource;
import com.tyron.builder.groovy.scripts.Script;
import com.tyron.builder.groovy.scripts.ScriptSource;

/**
 * When stored into the persistent store, we want the script to be created with a predictable class name: we don't want the path of the script
 * to be used in the generated class name because the same contents can be used for scripts found in different paths. Since we are storing
 * each build file in a separate directory based on the hash of the script contents, we can use the same file name
 * for each class. When the script is going to be loaded from the cache, we will get this class and set the path to the script using {@link
 * Script#setScriptSource(ScriptSource)}
 */
public class RemappingScriptSource extends DelegatingScriptSource {
    public final static String MAPPED_SCRIPT = "_BuildScript_";

    public RemappingScriptSource(ScriptSource source) {
        super(source);
    }

    @Override
    public String getClassName() {
        return MAPPED_SCRIPT;
    }

}
