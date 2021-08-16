package com.tyron.code;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import javax.tools.StandardJavaFileManager;
import javax.tools.DiagnosticCollector;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.StandardLocation;
import java.io.File;
import java.util.Collections;

public class JavaCompilerService implements CompilerProvider {

    private static JavacTool systemProvider = JavacTool.create();

    public JavacTask task;
    public StandardJavaFileManager fileManager;
    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector();
    
    public JavaCompilerService(File rootDir) {
      
        try {
            fileManager = systemProvider.getStandardFileManager(collector, null, null);

            fileManager.setLocation(StandardLocation.CLASS_PATH, Collections.singleton(rootDir));
            Iterable<JavaFileObject> sources = fileManager.list(
                StandardLocation.CLASS_PATH,
                "",
                Collections.singleton(JavaFileObject.Kind.SOURCE),
                true);
            task = systemProvider.getTask(null, fileManager, collector,
                                          null,
                                          null,
                                          sources);

           
            
        } catch (IOException e) {

        }

    }

    @Override
    public Set<String> imports() {
        return null;
    }


    @Override
    public List<String> publicTopLevelTypes() {
        return null;
    }

    @Override
    public List<String> packagePrivateTopLevelTypes(String packageName) {
        return null;
    }

    @Override
    public Iterable<Path> search(String query) {
        return null;
    }

    @Override
    public Optional<JavaFileObject> findAnywhere(String className) {
        return null;
    }

    @Override
    public Path findTypeDeclaration(String className) {
        return null;
    }

    @Override
    public Path[] findTypeReferences(String className) {
        return null;
    }

    @Override
    public Path[] findMemberReferences(String className, String memberName) {
        return null;
    }

    @Override
    public ParseTask parse(Path file) {
        return null;
    }

    @Override
    public ParseTask parse(JavaFileObject file) {
        return null;
    }

    @Override
    public CompileTask compile(Path... files) {
        return null;
    }

    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        return null;
    }

}
