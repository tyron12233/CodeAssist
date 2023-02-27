package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexId;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.AbstractForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.UnsyncByteArrayInputStream;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;

final class ValueSerializationChecker<Value, Input> {
    private static final Logger LOG = Logger.getInstance(ValueSerializationChecker.class);

    private final @NonNull DataExternalizer<Value> myValueExternalizer;
    private final @NonNull IndexId<?, Value> myIndexId;
    private final @NonNull ValueSerializationProblemReporter myProblemReporter;

    ValueSerializationChecker(@NonNull IndexExtension<?, Value, ?> extension,
                              @NonNull ValueSerializationProblemReporter reporter) {
        myValueExternalizer = extension.getValueExternalizer();
        myIndexId = extension.getName();
        myProblemReporter = reporter;
    }

    void checkValueSerialization(@NonNull Map<?, Value> data, @NonNull Input input) {
        if (IndexDebugProperties.DEBUG && !IndexDebugProperties.IS_IN_STRESS_TESTS) {
            Exception problem = getValueSerializationProblem(data, input);
            if (problem != null) {
                myProblemReporter.reportProblem(problem);
            }
        }
    }

    private @Nullable Exception getValueSerializationProblem(@NonNull Map<?, Value> data,
                                                             @NonNull Input input) {
        for (Map.Entry<?, Value> e : data.entrySet()) {
            final Value value = e.getValue();
            if (!(Comparing.equal(value, value) &&
                  (value == null || value.hashCode() == value.hashCode()))) {
                return new Exception("Index " +
                                     myIndexId +
                                     " violates equals / hashCode contract for Value parameter");
            }

            try {
                ByteArraySequence sequence = AbstractForwardIndexAccessor.serializeValueToByteSeq(
                        value,
                        myValueExternalizer,
                        4);
                Value deserializedValue = sequence ==
                                          null ? null :
                        myValueExternalizer.read(new DataInputStream(
                        new UnsyncByteArrayInputStream(sequence.getBytes(),
                                sequence.getOffset(),
                                sequence.getLength())));

                if (!(Comparing.equal(value, deserializedValue) &&
                      (value == null || value.hashCode() == deserializedValue.hashCode()))) {
                    LOG.error(("Index " +
                               myIndexId +
                               " deserialization violates equals / hashCode contract for Value " +
                               "parameter") +
                              " while indexing " +
                              input +
                              ". Original value: '" +
                              value +
                              "'; Deserialized value: '" +
                              deserializedValue +
                              "'");
                }
            } catch (IOException ex) {
                return ex;
            }
        }
        return null;
    }

    static final ValueSerializationProblemReporter DEFAULT_SERIALIZATION_PROBLEM_REPORTER =
            ex -> LOG.error(ex);
}