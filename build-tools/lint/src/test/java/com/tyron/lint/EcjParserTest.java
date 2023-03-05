package com.tyron.lint;

import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.junit.Test;

public class EcjParserTest {

    @Test
    public void test() {
        FileSystem fileSystem = new FileSystem(new String[0], new String[0], "UTF-8");
        Compiler compiler = new Compiler(
                fileSystem,
                DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                new CompilerOptions(),
                null,
                new DefaultProblemFactory(),
                null);
    }
}
