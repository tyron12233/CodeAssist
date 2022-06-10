package org.gradle.api.internal.initialization;

import org.gradle.api.internal.DomainObjectContext;
import org.gradle.groovy.scripts.ScriptSource;

public interface ScriptHandlerFactory {
    ScriptHandlerInternal create(ScriptSource scriptSource, ClassLoaderScope classLoaderScope);

    ScriptHandlerInternal create(ScriptSource scriptSource, ClassLoaderScope classLoaderScope, DomainObjectContext context);
}
