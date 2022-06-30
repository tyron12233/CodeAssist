package com.tyron.builder.configuration;

import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.groovy.scripts.internal.BuildScriptData;
import com.tyron.builder.groovy.scripts.internal.CompileOperation;

public interface CompileOperationFactory {
    CompileOperation<?> getPluginsBlockCompileOperation(ScriptTarget initialPassScriptTarget);

    CompileOperation<BuildScriptData> getScriptCompileOperation(ScriptSource scriptSource, ScriptTarget scriptTarget);
}
