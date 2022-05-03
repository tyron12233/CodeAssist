package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildException;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

import java.lang.reflect.Field;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;

@SuppressWarnings("deprecation")
class CustomCompilationUnit extends CompilationUnit {

    public CustomCompilationUnit(CompilerConfiguration compilerConfiguration, CodeSource codeSource, final Action<? super ClassNode> customVerifier, GroovyClassLoader groovyClassLoader, Map<String, List<String>> simpleNameToFQN) {
        super(compilerConfiguration, codeSource, groovyClassLoader);
        this.resolveVisitor = new GradleResolveVisitor(this, simpleNameToFQN);
        installCustomCodegen(customVerifier);
    }

    private void installCustomCodegen(Action<? super ClassNode> customVerifier) {
        final IPrimaryClassNodeOperation nodeOperation = prepareCustomCodegen(customVerifier);
        addFirstPhaseOperation(nodeOperation, Phases.CLASS_GENERATION);
    }

    @Override
    public void addPhaseOperation(IPrimaryClassNodeOperation op, int phase) {
        if (phase != Phases.CLASS_GENERATION) {
            super.addPhaseOperation(op, phase);
        }
    }

    // this is using a decoration of the existing classgen implementation
    // it can't be implemented as a phase as our customVerifier needs to visit closures as well
    private IPrimaryClassNodeOperation prepareCustomCodegen(Action<? super ClassNode> customVerifier) {
        try {
            final Field classgen = getClassgenField();
            IPrimaryClassNodeOperation realClassgen = (IPrimaryClassNodeOperation) classgen.get(this);
            final IPrimaryClassNodeOperation decoratedClassgen = decoratedNodeOperation(customVerifier, realClassgen);
            classgen.set(this, decoratedClassgen);
            return decoratedClassgen;
        } catch (ReflectiveOperationException e) {
            throw new BuildException("Unable to install custom rules code generation", e);
        }
    }

    private Field getClassgenField() {
        try {
            final Field classgen = CompilationUnit.class.getDeclaredField("classgen");
            classgen.setAccessible(true);
            return classgen;
        } catch (NoSuchFieldException e) {
            throw new BuildException("Unable to detect class generation in Groovy CompilationUnit", e);
        }
    }

    private static IPrimaryClassNodeOperation decoratedNodeOperation(Action<? super ClassNode> customVerifier, IPrimaryClassNodeOperation realClassgen) {
        return new IPrimaryClassNodeOperation() {

            @Override
            public boolean needSortedInput() {
                return realClassgen.needSortedInput();
            }

            @Override
            public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
                customVerifier.execute(classNode);
                realClassgen.call(source, context, classNode);
            }
        };
    }

}
