package com.tyron.builder.internal.logging.serializer;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.logging.events.LogLevelChangeEvent;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

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
