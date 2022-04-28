package com.tyron.completion.java.rewrite;

import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.model.Rewrite;

/**
 * A rewrite interface for java operations that needs the
 * {@link CompilerProvider}
 */
public interface JavaRewrite extends Rewrite<CompilerProvider> {

}
