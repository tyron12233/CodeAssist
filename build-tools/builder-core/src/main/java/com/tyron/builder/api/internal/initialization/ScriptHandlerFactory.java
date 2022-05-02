package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.groovy.scripts.ScriptSource;

public interface ScriptHandlerFactory {
    ScriptHandlerInternal create(ScriptSource scriptSource, ClassLoaderScope classLoaderScope);

    ScriptHandlerInternal create(ScriptSource scriptSource, ClassLoaderScope classLoaderScope, DomainObjectContext context);
}
