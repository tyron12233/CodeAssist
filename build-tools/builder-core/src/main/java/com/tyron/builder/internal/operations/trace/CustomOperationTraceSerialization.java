package com.tyron.builder.internal.operations.trace;

/**
 * Can be implemented by an operation details, result or progress object
 * to provide a custom form for serializing into the trace files.
 *
 * By default, objects are serialized using Groovy's reflective JSON serializer.
 */
public interface CustomOperationTraceSerialization {

    Object getCustomOperationTraceSerializableModel();

}
