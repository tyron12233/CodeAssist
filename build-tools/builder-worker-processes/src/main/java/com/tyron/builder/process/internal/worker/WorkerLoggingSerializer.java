package com.tyron.builder.process.internal.worker;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.logging.events.LogEvent;
import com.tyron.builder.internal.logging.events.LogLevelChangeEvent;
import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.internal.logging.serializer.LogEventSerializer;
import com.tyron.builder.internal.logging.serializer.LogLevelChangeEventSerializer;
import com.tyron.builder.internal.logging.serializer.SpanSerializer;
import com.tyron.builder.internal.logging.serializer.StyledTextOutputEventSerializer;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.serialize.BaseSerializerFactory;
import com.tyron.builder.internal.serialize.DefaultSerializerRegistry;
import com.tyron.builder.internal.serialize.ListSerializer;
import com.tyron.builder.internal.serialize.Serializer;
import com.tyron.builder.internal.serialize.SerializerRegistry;

public class WorkerLoggingSerializer {

    public static SerializerRegistry create() {
        DefaultSerializerRegistry registry = new DefaultSerializerRegistry(false);

        BaseSerializerFactory factory = new BaseSerializerFactory();
        Serializer<LogLevel> logLevelSerializer = factory.getSerializerFor(LogLevel.class);
        Serializer<Throwable> throwableSerializer = factory.getSerializerFor(Throwable.class);

        // Log events
        registry.register(LogEvent.class, new LogEventSerializer(logLevelSerializer, throwableSerializer));
        registry.register(StyledTextOutputEvent.class, new StyledTextOutputEventSerializer(logLevelSerializer, new ListSerializer<StyledTextOutputEvent.Span>(new SpanSerializer(factory.getSerializerFor(StyledTextOutput.Style.class)))));
        registry.register(LogLevelChangeEvent.class, new LogLevelChangeEventSerializer(logLevelSerializer));

        return registry;
    }
}
