package com.tyron.completion.java.patterns;

import static com.tyron.completion.TestUtil.resolveBasePath;
import static com.tyron.completion.java.patterns.JavacTreePatterns.*;

import androidx.test.core.app.ApplicationProvider;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.completion.TestUtil;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.action.FindCurrentPath;

import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import javax.tools.JavaFileObject;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class PatternTest {

    private static final String TEXT = "package com.test; public class Test {" + "static String test; " + "public static " + "void main(String[] args) " + "{ System.out.println(\"test\"); " + "}\n" + "}";

    private Project mProject;
    private FileManager mFileManager;
    private File mRoot;
    private Module mModule;

    private JavaCompilerService mService;

    @Before
    public void setup() throws IOException {
        CompletionModule.initialize(ApplicationProvider.getApplicationContext());
        CompletionModule.setAndroidJar(new File(resolveBasePath(), "classpath/rt.jar"));
        CompletionModule.setLambdaStubs(new File(resolveBasePath(),
                "classpath/core-lambda-stubs" + ".jar"));

        JavaCompilerProvider provider = new JavaCompilerProvider();
        CompilerService.getInstance().registerIndexProvider(JavaCompilerProvider.KEY, provider);

        mRoot = new File(TestUtil.resolveBasePath(), "EmptyProject");
        mFileManager = new MockFileManager(mRoot);
        mProject = new Project(mRoot);
        mModule = new MockAndroidModule(mRoot, mFileManager);
        mModule.open();

        mService = provider.get(mProject, mModule);
    }
    @Test
    public void test() throws IOException {

        JavaFileObject javaFileObject = new SourceFileObject(Paths.get("test.java"), TEXT, Instant.now());

        CompilerContainer compile = mService.compile(Collections.singletonList(javaFileObject));
        compile.run(task -> {
            CompilationUnitTree root = task.root();
            Trees trees = Trees.instance(task.task);

            FindCurrentPath findCurrentPath = new FindCurrentPath(task.task);
            TreePath scan = findCurrentPath.scan(root, 120L);

            ProcessingContext context = new ProcessingContext();
            context.put("trees", trees);
            context.put("elements", task.task.getElements());
            context.put("root", root);

            // String literal that is the first parameter of a method
            // called println that is defined in PrintStream
            JavacTreePattern.Capture<LiteralTree> capture =
                    literal().methodCallParameter(
                            0,
                            method()
                                    .withName("println")
                                    .definedInClass("java.io.PrintStream")


                    );
            // "test" -> println() -> java.io.PrintStream
            assert capture.accepts(scan.getLeaf(), context);

            // a class with field of name test and type String
            ClassTreePattern test = classTree().withField(false,
                    variable()
                            .withName("test")
                            .withType("java.lang.String"));
            assert test.accepts(root.getTypeDecls().get(0), context);

            ClassTreePattern main = classTree().withMethod(false,
                    method().withName("main"));
            assert  main.accepts(root.getTypeDecls().get(0), context);
        });
    }
}
