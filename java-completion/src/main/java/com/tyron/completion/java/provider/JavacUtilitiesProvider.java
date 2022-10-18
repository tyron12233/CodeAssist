package com.tyron.completion.java.provider;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;

import com.sun.tools.javac.util.Context;
import com.tyron.builder.project.Project;

import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public interface JavacUtilitiesProvider {

    Context getContext();

    Trees getTrees();

    Elements getElements();

    Types getTypes();

    CompilationUnitTree root();

    Project getProject();
}
