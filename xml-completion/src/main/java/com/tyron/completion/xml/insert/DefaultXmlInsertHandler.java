package com.tyron.completion.xml.insert;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.model.CompletionItem;

import java.util.function.Predicate;

public class DefaultXmlInsertHandler extends DefaultInsertHandler {

    private static final Predicate<Character> DEFAULT_PREDICATE =
            ch -> Character.isJavaIdentifierPart(ch)
                    || ch == '<'
                    || ch == '/'
                    || ch == ':'
                    || ch == '.';

    public DefaultXmlInsertHandler(CompletionItem item) {
        super(DEFAULT_PREDICATE, item);
    }
}
