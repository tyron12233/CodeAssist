package com.tyron.code;
import java.util.Set;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import javax.tools.JavaFileObject;
import java.nio.file.Paths;

public interface CompilerProvider {
    
    Set<String> imports();
    
    List<String> publicTopLevelTypes();

    List<String> packagePrivateTopLevelTypes(String packageName);

    Iterable<Path> search(String query);

    Optional<JavaFileObject> findAnywhere(String className);

    Path findTypeDeclaration(String className);

    Path[] findTypeReferences(String className);

    Path[] findMemberReferences(String className, String memberName);

    ParseTask parse(Path file);

    ParseTask parse(JavaFileObject file);

    CompileTask compile(Path... files);

    CompileTask compile(Collection<? extends JavaFileObject> sources);

    Path NOT_FOUND = Paths.get("");
}
