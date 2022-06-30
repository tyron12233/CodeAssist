package com.tyron.builder.internal.logging.slf4j;


import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.Logger;

import javax.annotation.Nullable;

public interface ContextAwareTaskLogger extends Logger {
    interface MessageRewriter {

        /**
         * Rewrites log message.
         *
         * @param logLevel the logging level
         * @param message the original message
         * @return the rewritten message or null if this message should be silenced
         */
        @Nullable
        String rewrite(LogLevel logLevel, String message);
    }

    void setMessageRewriter(MessageRewriter messageRewriter);

    void setFallbackBuildOperationId(OperationIdentifier operationIdentifier);
}