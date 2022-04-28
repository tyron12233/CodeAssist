package com.tyron.builder.internal.execution.history.changes;

import com.google.common.collect.ImmutableMap;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FingerprintingStrategy;
import com.tyron.builder.internal.fingerprint.impl.AbsolutePathFingerprintingStrategy;
import com.tyron.builder.internal.fingerprint.impl.IgnoredPathFingerprintingStrategy;
import com.tyron.builder.internal.fingerprint.impl.NameOnlyFingerprintingStrategy;
import com.tyron.builder.internal.fingerprint.impl.RelativePathFingerprintingStrategy;

import java.util.SortedMap;

public abstract class AbstractFingerprintChanges implements ChangeContainer {
    private static final ImmutableMap<String, FingerprintCompareStrategy> COMPARE_STRATEGY_MAPPING = ImmutableMap.<String, FingerprintCompareStrategy>builder()
            .put(AbsolutePathFingerprintingStrategy.IDENTIFIER, AbsolutePathFingerprintCompareStrategy.INSTANCE)
            .put(NameOnlyFingerprintingStrategy.IDENTIFIER, NormalizedPathFingerprintCompareStrategy.INSTANCE)
            .put(RelativePathFingerprintingStrategy.IDENTIFIER, NormalizedPathFingerprintCompareStrategy.INSTANCE)
            .put(IgnoredPathFingerprintingStrategy.IDENTIFIER, IgnoredPathCompareStrategy.INSTANCE)
            .put(FingerprintingStrategy.CLASSPATH_IDENTIFIER, ClasspathCompareStrategy.INSTANCE)
            .put(FingerprintingStrategy.COMPILE_CLASSPATH_IDENTIFIER, ClasspathCompareStrategy.INSTANCE)
            .build();

    protected final SortedMap<String, FileCollectionFingerprint> previous;
    protected final SortedMap<String, CurrentFileCollectionFingerprint> current;
    private final String title;

    protected AbstractFingerprintChanges(SortedMap<String, FileCollectionFingerprint> previous, SortedMap<String, CurrentFileCollectionFingerprint> current, String title) {
        this.previous = previous;
        this.current = current;
        this.title = title;
    }

    @Override
    public boolean accept(ChangeVisitor visitor) {
        return SortedMapDiffUtil.diff(previous, current, new PropertyDiffListener<String, FileCollectionFingerprint, CurrentFileCollectionFingerprint>() {
            @Override
            public boolean removed(String previousProperty) {
                return true;
            }

            @Override
            public boolean added(String currentProperty) {
                return true;
            }

            @Override
            public boolean updated(String property, FileCollectionFingerprint previousFingerprint, CurrentFileCollectionFingerprint currentFingerprint) {
                String propertyTitle = title + " property '" + property + "'";
                FingerprintCompareStrategy compareStrategy = determineCompareStrategy(currentFingerprint);
                return compareStrategy.visitChangesSince(previousFingerprint, currentFingerprint, propertyTitle, visitor);
            }
        });
    }

    protected FingerprintCompareStrategy determineCompareStrategy(CurrentFileCollectionFingerprint currentFingerprint) {
        return COMPARE_STRATEGY_MAPPING.get(currentFingerprint.getStrategyIdentifier());
    }
}