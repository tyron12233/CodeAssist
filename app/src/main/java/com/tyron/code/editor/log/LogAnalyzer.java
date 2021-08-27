package com.tyron.code.editor.log;

import android.util.Log;

import com.android.tools.r8.v.b.P;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Matcher;
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
