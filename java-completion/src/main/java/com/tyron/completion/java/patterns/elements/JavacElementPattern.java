package com.tyron.completion.java.patterns.elements;

import androidx.annotation.NonNull;

import com.tyron.completion.java.patterns.JavacTreeElementPattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.TreeElementPattern;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

public interface JavacElementPattern {
    boolean accepts(Element element, ProcessingContext context);
}