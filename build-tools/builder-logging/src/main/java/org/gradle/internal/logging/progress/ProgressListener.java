package org.gradle.internal.logging.progress;

import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scope;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.logging.events.ProgressEvent;
import org.gradle.internal.logging.events.ProgressStartEvent;

@EventScope(Scope.Global.class)
public interface ProgressListener {
    void started(ProgressStartEvent event);

    void progress(ProgressEvent event);

    void completed(ProgressCompleteEvent event);
}
