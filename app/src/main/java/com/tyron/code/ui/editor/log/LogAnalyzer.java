package com.tyron.code.ui.editor.log;

import java.util.regex.Pattern;

import io.github.rosemoe.editor.interfaces.CodeAnalyzer;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import io.github.rosemoe.editor.text.TextAnalyzer;

public class LogAnalyzer implements CodeAnalyzer {

    Pattern pattern = Pattern.compile("<\\$\\$(.+?)>(.+?)<\\$\\$/(.+?)>", Pattern.DOTALL);

    @Override
    public void analyze(CharSequence content, TextAnalyzeResult colors, TextAnalyzer.AnalyzeThread.Delegate delegate) {

    }
}
