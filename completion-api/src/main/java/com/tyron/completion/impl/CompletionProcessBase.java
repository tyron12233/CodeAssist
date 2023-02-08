package com.tyron.completion.impl;

import com.tyron.completion.CompletionProcess;

import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;

public interface CompletionProcessBase extends CompletionProcess {

  void addWatchedPrefix(int startOffset, ElementPattern<String> restartCondition);

}