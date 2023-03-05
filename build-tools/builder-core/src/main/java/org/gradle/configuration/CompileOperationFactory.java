package org.gradle.configuration;

import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.groovy.scripts.internal.BuildScriptData;
import org.gradle.groovy.scripts.internal.CompileOperation;

public interface CompileOperationFactory {
    CompileOperation<?> getPluginsBlockCompileOperation(ScriptTarget initialPassScriptTarget);

    CompileOperation<BuildScriptData> getScriptCompileOperation(ScriptSource scriptSource, ScriptTarget scriptTarget);
}
