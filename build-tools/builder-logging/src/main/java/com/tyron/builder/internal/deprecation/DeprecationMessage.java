package com.tyron.builder.internal.deprecation;

class DeprecationMessage {

    private final String summary;
    private final String removalDetails;
    private final String advice;
    private final String context;
    private final Documentation documentation;
    private final DeprecatedFeatureUsage.Type usageType;

    DeprecationMessage(String summary, String removalDetails, String advice, String context, Documentation documentation, DeprecatedFeatureUsage.Type usageType) {
        this.summary = summary;
        this.removalDetails = removalDetails;
        this.advice = advice;
        this.context = context;
        this.documentation = documentation;
        this.usageType = usageType;
    }

    DeprecatedFeatureUsage toDeprecatedFeatureUsage(Class<?> calledFrom) {
        return new DeprecatedFeatureUsage(summary, removalDetails, advice, context, documentation, usageType, calledFrom);
    }

}
