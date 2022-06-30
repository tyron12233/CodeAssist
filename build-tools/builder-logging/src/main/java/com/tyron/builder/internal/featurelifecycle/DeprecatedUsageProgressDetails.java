package com.tyron.builder.internal.featurelifecycle;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A usage of some deprecated API or feature.
 *
 * @since 4.10
 */
public interface DeprecatedUsageProgressDetails {

    /**
     * See {@link com.tyron.builder.internal.deprecation.DeprecatedFeatureUsage#getSummary()}
     */
    String getSummary();

    /**
     * See {@link com.tyron.builder.internal.deprecation.DeprecatedFeatureUsage#getRemovalDetails()}
     */
    String getRemovalDetails();

    /**
     * See {@link com.tyron.builder.internal.deprecation.DeprecatedFeatureUsage#getAdvice()}
     */
    @Nullable
    String getAdvice();

    /**
     * See {@link com.tyron.builder.internal.deprecation.DeprecatedFeatureUsage#getContextualAdvice()}
     */
    @Nullable
    String getContextualAdvice();

    /**
     * See {@link com.tyron.builder.internal.deprecation.DeprecatedFeatureUsage#getDocumentationUrl()}
     *
     * @since 6.2
     */
    @Nullable
    String getDocumentationUrl();

    /**
     * See {@link com.tyron.builder.internal.deprecation.DeprecatedFeatureUsage#getType()}.
     *
     * Value is always of {@link DeprecatedFeatureUsage.Type#name()}.
     */
    String getType();

    /**
     * See {@link com.tyron.builder.internal.deprecation.DeprecatedFeatureUsage#getStack()}
     */
    List<StackTraceElement> getStackTrace();

}
