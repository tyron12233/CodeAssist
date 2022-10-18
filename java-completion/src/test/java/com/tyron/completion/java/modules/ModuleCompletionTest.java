package com.tyron.completion.java.modules;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.tyron.builder.BuildModule;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.completion.java.parse.CompilationInfoImpl;
import com.tyron.completion.java.parse.JavacParser;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

import kotlin.io.FilesKt;

public class ModuleCompletionTest {

    public static class SymbolTable extends Symtab {

        protected SymbolTable(Context context) throws Symbol.CompletionFailure {
            super(context);
        }
    }

    @Before
    public void setup() {
    }

    @Test
    public void enterJarFile() throws IOException {
        File testFile = Files.createTempFile("Test", ".java").toFile();

        Context context = new Context();
        Symtab instance = SymbolTable.instance(context);

        System.out.println();
    }

}
