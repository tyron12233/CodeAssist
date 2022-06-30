package com.tyron.builder.internal.featurelifecycle;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.internal.deprecation.DeprecatedFeatureUsage;

import java.util.List;

public class DefaultDeprecatedUsageProgressDetails implements DeprecatedUsageProgressDetails {

    @VisibleForTesting
    public final DeprecatedFeatureUsage featureUsage;

    public DefaultDeprecatedUsageProgressDetails(DeprecatedFeatureUsage featureUsage) {
        this.featureUsage = featureUsage;
    }

    @Override
    public String getSummary() {
        return featureUsage.getSummary();
    }

    @Override
    public String getRemovalDetails() {
        return featureUsage.getRemovalDetails();
    }

    @Override
    public String getAdvice() {
        return featureUsage.getAdvice();
    }

    @Override
    public String getContextualAdvice() {
        return featureUsage.getContextualAdvice();
    }

    @Override
    public String getDocumentationUrl() {
        return featureUsage.getDocumentationUrl();
    }

    @Override
    public String getType() {
        return featureUsage.getType().name();
    }

    @Override
    public List<StackTraceElement> getStackTrace() {
        return featureUsage.getStack();
    }
}
