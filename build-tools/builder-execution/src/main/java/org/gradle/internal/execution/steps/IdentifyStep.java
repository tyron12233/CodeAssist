package org.gradle.internal.execution.steps;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.cache.Cache;
import org.gradle.internal.Try;
import org.gradle.internal.execution.DeferredExecutionHandler;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.UnitOfWork.Identity;
import org.gradle.internal.execution.WorkValidationContext;
import org.gradle.internal.execution.fingerprint.InputFingerprinter;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.snapshot.ValueSnapshot;

import javax.annotation.Nonnull;
import java.util.Optional;

public class IdentifyStep<C extends ExecutionRequestContext, R extends Result> implements DeferredExecutionAwareStep<C, R> {
    private final DeferredExecutionAwareStep<? super IdentityContext, R> delegate;

    public IdentifyStep(
            DeferredExecutionAwareStep<? super IdentityContext, R> delegate
    ) {
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        return delegate.execute(work, createIdentityContext(work, context));
    }

    @Override
    public <T, O> T executeDeferred(UnitOfWork work, C context, Cache<Identity, Try<O>> cache, DeferredExecutionHandler<O, T> handler) {
        return delegate.executeDeferred(work, createIdentityContext(work, context), cache, handler);
    }

    @Nonnull
    private IdentityContext createIdentityContext(UnitOfWork work, C context) {
        InputFingerprinter.Result inputs = work.getInputFingerprinter().fingerprintInputProperties(
                ImmutableSortedMap.of(),
                ImmutableSortedMap.of(),
                ImmutableSortedMap.of(),
                ImmutableSortedMap.of(),
                work::visitIdentityInputs
        );
        ImmutableSortedMap<String, ValueSnapshot> identityInputProperties = inputs.getValueSnapshots();
        ImmutableSortedMap<String, CurrentFileCollectionFingerprint> identityInputFileProperties = inputs.getFileFingerprints();

        Identity identity = work.identify(identityInputProperties, identityInputFileProperties);
        return new IdentityContext() {
            @Override
            public Optional<String> getNonIncrementalReason() {
                return context.getNonIncrementalReason();
            }

            @Override
            public WorkValidationContext getValidationContext() {
                return context.getValidationContext();
            }

            @Override
            public ImmutableSortedMap<String, ValueSnapshot> getInputProperties() {
                return identityInputProperties;
            }

            @Override
            public ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties() {
                return identityInputFileProperties;
            }

            @Override
            public Identity getIdentity() {
                return identity;
            }
        };
    }
}
