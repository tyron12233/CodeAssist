package org.gradle.internal.logging.serializer;

import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.events.LogLevelChangeEvent;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

public class LogLevelChangeEventSerializer implements Serializer<LogLevelChangeEvent> {
    private final Serializer<LogLevel> logLevelSerializer;

    public LogLevelChangeEventSerializer(Serializer<LogLevel> logLevelSerializer) {
        this.logLevelSerializer = logLevelSerializer;
    }

    @Override
    public void write(Encoder encoder, LogLevelChangeEvent value) throws Exception {
        logLevelSerializer.write(encoder, value.getNewLogLevel());
    }

    @Override
    public LogLevelChangeEvent read(Decoder decoder) throws Exception {
        LogLevel logLevel = logLevelSerializer.read(decoder);
        return new LogLevelChangeEvent(logLevel);
    }
}
