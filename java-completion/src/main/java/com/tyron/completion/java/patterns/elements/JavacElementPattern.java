package com.tyron.completion.java.patterns.elements;

import androidx.annotation.NonNull;

import com.tyron.completion.java.patterns.JavacTreeElementPattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.TreeElementPattern;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

public interface JavacElementPattern {
    boolean accepts(Element element, ProcessingContext context);
}