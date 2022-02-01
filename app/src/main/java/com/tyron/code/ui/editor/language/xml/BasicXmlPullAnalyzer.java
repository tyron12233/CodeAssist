package com.tyron.code.ui.editor.language.xml;

import com.tyron.builder.util.CharSequenceReader;
import com.tyron.code.ui.editor.language.HighlightUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import io.github.rosemoe.sora2.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora2.text.LineNumberCalculator;
import io.github.rosemoe.sora2.text.TextAnalyzeResult;
import io.github.rosemoe.sora2.text.TextAnalyzer;

public class BasicXmlPullAnalyzer implements CodeAnalyzer {

    @Override
    public void analyze(CharSequence content, TextAnalyzeResult result, TextAnalyzer.AnalyzeThread.Delegate delegate) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            XmlPullParser parser = factory.newPullParser();

            LineNumberCalculator calculator = new LineNumberCalculator(content);
            calculator.update(content.length());
            int errLine = 0;
            int errColumn = 0;
            parser.setInput(new CharSequenceReader(content));
            while (delegate.shouldAnalyze()) {
                try {
                    if (calculator.getLine() + 1 == parser.getLineNumber() &&
                            calculator.getColumn() + 1 == parser.getColumnNumber()) {
                        break;
                    }
                    parser.next();
                } catch (XmlPullParserException e) {
                    if (errLine == parser.getLineNumber() && errColumn == parser.getColumnNumber()) {
                        break;
                    }
                    errLine = parser.getLineNumber();
                    errColumn = parser.getColumnNumber();
                    HighlightUtil.setErrorSpan(result, errLine, errColumn);
                }
            }
        } catch (Exception ignored) {

        }
    }
}
