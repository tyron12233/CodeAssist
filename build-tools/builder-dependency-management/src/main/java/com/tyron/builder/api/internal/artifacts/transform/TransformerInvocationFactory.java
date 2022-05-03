//package com.tyron.builder.api.internal.artifacts.transform;
//
//import com.google.common.collect.ImmutableList;
//import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
//
//import javax.annotation.concurrent.ThreadSafe;
//import java.io.File;
//
//@ThreadSafe
//public interface TransformerInvocationFactory {
//    /**
//     * Returns an invocation which allows invoking the actual transformer.
//     */
//    CacheableInvocation<ImmutableList<File>> createInvocation(
//        Transformer transformer,
//        File inputArtifact,
//        ArtifactTransformDependencies dependencies,
//        TransformationSubject subject,
//        InputFingerprinter inputFingerprinter);
//}
