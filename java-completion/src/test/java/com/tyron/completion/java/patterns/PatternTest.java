package com.tyron.completion.java.patterns;

import com.tyron.completion.java.ParseTask;
import com.tyron.completion.java.Parser;
import com.tyron.completion.java.action.FindCurrentPath;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PlatformPatterns;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.NestingKind;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;

@RunWith(RobolectricTestRunner.class)
public class PatternTest {

    private static final String TEXT = "public class Test {" + "public static " + "void main() " + "{ " + "}\n" + "}";

    @Test
    public void test() {
        Parser parser = Parser.parseJavaFileObject(null, new JavaFileObject() {
            @Override
            public Kind getKind() {
                return Kind.SOURCE;
            }

            @Override
            public boolean isNameCompatible(String s, Kind kind) {
                return false;
            }

            @Override
            public NestingKind getNestingKind() {
                return null;
            }

            @Override
            public Modifier getAccessLevel() {
                return Modifier.PUBLIC;
            }

            @Override
            public URI toUri() {
                return null;
            }

            @Override
            public String getName() {
                return "Test.java";
            }

            @Override
            public InputStream openInputStream() throws IOException {
                return null;
            }

            @Override
            public OutputStream openOutputStream() throws IOException {
                return null;
            }

            @Override
            public Reader openReader(boolean b) throws IOException {
                return null;
            }

            @Override
            public CharSequence getCharContent(boolean b) throws IOException {
                return TEXT;
            }

            @Override
            public Writer openWriter() throws IOException {
                return null;
            }

            @Override
            public long getLastModified() {
                return 0;
            }

            @Override
            public boolean delete() {
                return false;
            }
        });

        CompilationUnitTree root = parser.root;
        Trees trees = parser.trees;

        FindCurrentPath findCurrentPath = new FindCurrentPath(parser.task);
        TreePath scan = findCurrentPath.scan(root, 46L);

        ProcessingContext context = new ProcessingContext();
        context.put("trees", trees);
        context.put("root", root);

        JavacTreePattern.Capture<Tree> treeCapture =
                JavacTreePatterns.tree()
                        .withParent(MethodTree.class);
        // block -> method
        assert treeCapture.accepts(scan.getLeaf(), context);
    }
}
