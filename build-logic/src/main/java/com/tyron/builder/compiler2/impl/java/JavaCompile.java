package com.tyron.builder.compiler2.impl.java;

/**
 * Compiles Java source files.
 */
public class JavaCompile extends AbstractCompile {

    public JavaCompile() {
        doFirst("test", it -> {
           System.out.println("Compiling java");
        });
    }


}
