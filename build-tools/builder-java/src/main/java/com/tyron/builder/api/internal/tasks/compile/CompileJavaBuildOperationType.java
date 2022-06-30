package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.internal.operations.BuildOperationType;

import javax.annotation.Nullable;
import java.util.List;

/**
 * @since 5.1
 */
//@NotUsedByScanPlugin("used to report annotation processor execution times to TAPI progress listeners")
public class CompileJavaBuildOperationType implements BuildOperationType<CompileJavaBuildOperationType.Details, CompileJavaBuildOperationType.Result> {

    public interface Details {
    }

    public interface Result {

        /**
         * Returns details about the used annotation processors, if available.
         *
         * <p>Details are only available if an instrumented compiler was used.
         *
         * @return details about used annotation processors; {@code null} if unknown.
         */
        @Nullable
        List<AnnotationProcessorDetails> getAnnotationProcessorDetails();

        /**
         * Details about an annotation processor used during compilation.
         */
        interface AnnotationProcessorDetails {

            /**
             * Returns the fully-qualified class name of this annotation processor.
             */
            String getClassName();

            /**
             * Returns the type of this annotation processor.
             */
            Type getType();

            /**
             * Returns the total execution time of this annotation processor.
             */
            long getExecutionTimeInMillis();

            /**
             * Type of annotation processor.
             *
             * @see IncrementalAnnotationProcessorType
             */
            enum Type {
                ISOLATING, AGGREGATING, UNKNOWN
            }

        }

    }

}
