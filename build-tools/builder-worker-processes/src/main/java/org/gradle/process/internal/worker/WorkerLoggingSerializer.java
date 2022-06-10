package org.gradle.process.internal.worker;

import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.events.LogEvent;
import org.gradle.internal.logging.events.LogLevelChangeEvent;
import org.gradle.internal.logging.events.StyledTextOutputEvent;
import org.gradle.internal.logging.serializer.LogEventSerializer;
import org.gradle.internal.logging.serializer.LogLevelChangeEventSerializer;
import org.gradle.internal.logging.serializer.SpanSerializer;
import org.gradle.internal.logging.serializer.StyledTextOutputEventSerializer;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.serialize.BaseSerializerFactory;
import org.gradle.internal.serialize.DefaultSerializerRegistry;
import org.gradle.internal.serialize.ListSerializer;
import org.gradle.internal.serialize.Serializer;
import org.gradle.internal.serialize.SerializerRegistry;

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
