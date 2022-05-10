package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.groovy.scripts.Transformer;
import com.tyron.builder.internal.serialize.Serializer;

/**
 * A stateful “backing” for a compilation operation.
 * <p>
 * The compilation may extract data from the source under compilation, made available after compilation by {@link #getExtractedData()}.
 * The exposed transformer typically gathers the data while transforming.
 * <p>
 * As these objects are stateful, they can only be used for a single compile operation.
 *
 * @param <T> the type of data extracted by this operation
 */
public interface CompileOperation<T> {

    /**
     * A unique id for this operations.
     * <p>
     * Used to distinguish between the classes compiled from the same script with different transformers, so should be a valid java identifier.
     */
    String getId();

    /**
     * The stage of this compile operation.
     * This is exposed by {@link CompileScriptBuildOperationType.Details#getStage()}.
     * */
    String getStage();

    Transformer getTransformer();

    /**
     * The data extracted from the script. Note that this method may be called without the transformer ever being invoked, in this case of an empty script.
     */
    T getExtractedData();

    Serializer<T> getDataSerializer();

}
