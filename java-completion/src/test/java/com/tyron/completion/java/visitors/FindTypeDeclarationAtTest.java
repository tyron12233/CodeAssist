package com.tyron.completion.java.visitors;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.completion.java.FindTypeDeclarationAt;
import com.tyron.completion.java.compiler.Parser;

import org.intellij.lang.annotations.Language;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.sun.source.tree.ClassTree;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.nio.file.Paths;
import java.time.Instant;

@RunWith(RobolectricTestRunner.class)
public class FindTypeDeclarationAtTest {

    private static final String CURSOR = "/** CURSOR */";

    @Language("JAVA")
    private static final String SOURCE =
            "import android.app.AlertDialog;\n" + "import android.content.DialogInterface;\n" +
                                         "\n" +
                                         "class Main {\n" + "    public static void main() {\n" +
                                         "        new AlertDialog.Builder(null)\n" +
            ".setPositiveButton(\"test\", new " +
            "DialogInterface" + ".OnCancelListener() {\n" + "/** CURSOR */    \n" + "})\n" +
                                         "  " +
                                         "  " +
                                         "}\n" +
                                         "}";

    @Test
    public void test() {
        SourceFileObject fileObject = new SourceFileObject(Paths.get("Main.java"), SOURCE, Instant.now());
        Parser parser = Parser.parseJavaFileObject(null, fileObject);
        assert parser.root != null;

        ClassTree scan = new FindTypeDeclarationAt(parser.task).scan(parser.root,
                                                                     (long) SOURCE.indexOf(CURSOR));
        assert scan != null;

        System.out.println(scan);
    }
}
