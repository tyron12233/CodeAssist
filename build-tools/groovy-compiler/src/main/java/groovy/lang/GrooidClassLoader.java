package groovy.lang;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;

import java.security.AccessController;
import java.security.PrivilegedAction;

import groovyjarjarasm.asm.ClassWriter;

public class GrooidClassLoader extends GroovyClassLoader {

    public GrooidClassLoader(ClassLoader loader, CompilerConfiguration config) {
        super(loader, config);
    }

    public GrooidClassLoader(ClassLoader parent, CompilerConfiguration configuration, boolean b) {
        super(parent, configuration, b);
    }

    @Override
    protected ClassCollector createCollector(CompilationUnit unit, SourceUnit su) {
        InnerLoader loader = AccessController.doPrivileged((PrivilegedAction<InnerLoader>) () -> new InnerLoader(GrooidClassLoader.this));
        //noinspection rawtypes
        return new ClassCollector(loader, unit, su) {

            @Override
            protected Class createClass(byte[] code, ClassNode classNode) {
                return super.createClass(code, classNode);
            }

            @Override
            protected Class onClassNode(ClassWriter classWriter, ClassNode classNode) {
                try {
                    return super.onClassNode(classWriter, classNode);
                } catch (Exception e) {
                    return null;
                }
            }
        };
    }
}