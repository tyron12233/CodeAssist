package com.tyron.builder.internal.logging.progress;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scope;
import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
import com.tyron.builder.internal.logging.events.ProgressEvent;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;

@EventScope(Scope.Global.class)
public interface ProgressListener {
    void started(ProgressStartEvent event);

    void progress(ProgressEvent event);

    void completed(ProgressCompleteEvent event);
}
