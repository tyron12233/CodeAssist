package io.github.rosemoe.sora2.langs.java;

import io.github.rosemoe.sora2.text.TextAnalyzeResult;
import io.github.rosemoe.sora2.util.TrieTree;
import io.github.rosemoe.sora2.widget.EditorColorScheme;
import io.github.rosemoe.sora2.text.LineNumberCalculator;
import io.github.rosemoe.sora2.text.TextAnalyzer;
import io.github.rosemoe.sora2.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora2.data.BlockLine;
import io.github.rosemoe.sora2.data.NavigationItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Note:Navigation not supported
 *
 * @author Rose
 */
public class JavaCodeAnalyzer implements CodeAnalyzer {

    private final static Object OBJECT = new Object();

    @Override
    public void analyze(CharSequence content, TextAnalyzeResult result, TextAnalyzer.AnalyzeThread.Delegate delegate) {
        StringBuilder text = content instanceof StringBuilder ? (StringBuilder) content : new StringBuilder(content);
        JavaTextTokenizer tokenizer = new JavaTextTokenizer(text);
        tokenizer.setCalculateLineColumn(false);
        Tokens token, previous = Tokens.UNKNOWN;
        int line = 0, column = 0;
        LineNumberCalculator helper = new LineNumberCalculator(text);
        //IdentifierAutoComplete.Identifiers identifiers = new IdentifierAutoComplete.Identifiers();
       // identifiers.begin();
        Stack<BlockLine> stack = new Stack<>();
        List<NavigationItem> labels = new ArrayList<>();
        int maxSwitch = 1, currSwitch = 0;
        //Tree to save class names and query
        TrieTree<Object> classNames = new TrieTree<>();
        //Whether previous token is class name
        boolean classNamePrevious = false;
        //Add default class name
        classNames.put("String", OBJECT);
        classNames.put("Object", OBJECT);
        boolean first = true;
        while (delegate.shouldAnalyze()) {
            try {
                // directNextToken() does not skip any token
                token = tokenizer.directNextToken();
            } catch (RuntimeException e) {
                //When a spelling input is in process, this will happen because of format mismatch
                token = Tokens.CHARACTER_LITERAL;
            }
            if (token == Tokens.EOF) {
                break;
            }
            // Backup values because looking ahead in function name match will change them
            int thisIndex = tokenizer.getIndex();
            int thisLength = tokenizer.getTokenLength();
            switch (token) {
                case WHITESPACE:
                case NEWLINE:
                    if (first) {
                        result.addNormalIfNull();
                    }
                    break;
                case IDENTIFIER:
                    //Add a identifier to auto complete
                  //  identifiers.addIdentifier(text.substring(tokenizer.getIndex(), tokenizer.getTokenLength() + tokenizer.getIndex()));
                    //The previous so this will be the annotation's type name
                    if (previous == Tokens.AT) {
                        result.addIfNeeded(line, column, EditorColorScheme.ANNOTATION);
                        break;
                    }
                    //Here we have to get next token to see if it is function
                    //We can only get the next token in stream.
                    //If more tokens required, we have to use a stack in tokenizer
                    Tokens next = tokenizer.directNextToken();
                    //The next is LPAREN,so this is function name or type name
                    if (next == Tokens.LPAREN) {
                        result.addIfNeeded(line, column, EditorColorScheme.FUNCTION_NAME);
                        tokenizer.pushBack(tokenizer.getTokenLength());
                        break;
                    }
                    //Push back the next token
                    tokenizer.pushBack(tokenizer.getTokenLength());
                    //This is a class definition
                    if (previous == Tokens.CLASS) {
                        result.addIfNeeded(line, column, EditorColorScheme.IDENTIFIER_NAME);
                        //Add class name
                        classNames.put(text, thisIndex, thisLength, OBJECT);
                        break;
                    }
                    //Has class name
                    if (classNames.get(text, thisIndex, thisLength) == OBJECT) {
                        result.addIfNeeded(line, column, EditorColorScheme.IDENTIFIER_NAME);
                        //Mark it
                        classNamePrevious = true;
                        break;
                    }
                    if (classNamePrevious) {
                        //Var name
                        result.addIfNeeded(line, column, EditorColorScheme.IDENTIFIER_VAR);
                        classNamePrevious = false;
                        break;
                    }
                    result.addIfNeeded(line, column, EditorColorScheme.TEXT_NORMAL);
                    break;
                case CHARACTER_LITERAL:
                case STRING:
                case FLOATING_POINT_LITERAL:
                case INTEGER_LITERAL:
                    classNamePrevious = false;
                    result.addIfNeeded(line, column, EditorColorScheme.LITERAL);
                    break;
                case INT:
                case LONG:
                case BOOLEAN:
                case BYTE:
                case CHAR:
                case FLOAT:
                case DOUBLE:
                case SHORT:
                case VOID:
                    classNamePrevious = true;
                    result.addIfNeeded(line, column, EditorColorScheme.KEYWORD);
                    break;
                case ABSTRACT:
                case ASSERT:
                case CLASS:
                case DO:
                case FINAL:
                case FOR:
                case IF:
                case NEW:
                case PUBLIC:
                case PRIVATE:
                case PROTECTED:
                case PACKAGE:
                case RETURN:
                case STATIC:
                case SUPER:
                case SWITCH:
                case ELSE:
                case VOLATILE:
                case SYNCHRONIZED:
                case STRICTFP:
                case GOTO:
                case CONTINUE:
                case BREAK:
                case TRANSIENT:
                case TRY:
                case CATCH:
                case FINALLY:
                case WHILE:
                case CASE:
                case DEFAULT:
                case CONST:
                case ENUM:
                case EXTENDS:
                case IMPLEMENTS:
                case IMPORT:
                case INSTANCEOF:
                case INTERFACE:
                case NATIVE:
                case THIS:
                case THROW:
                case THROWS:
                case TRUE:
                case FALSE:
                case NULL:
                    classNamePrevious = false;
                    result.addIfNeeded(line, column, EditorColorScheme.KEYWORD);
                    break;
                case LBRACE: {
                    classNamePrevious = false;
                    result.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                    if (stack.isEmpty()) {
                        if (currSwitch > maxSwitch) {
                            maxSwitch = currSwitch;
                        }
                        currSwitch = 0;
                    }
                    currSwitch++;
                    BlockLine block = result.obtainNewBlock();
                    block.startLine = line;
                    block.startColumn = column;
                    stack.push(block);
                    break;
                }
                case RBRACE: {
                    classNamePrevious = false;
                    result.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                    if (!stack.isEmpty()) {
                        BlockLine block = stack.pop();
                        block.endLine = line;
                        block.endColumn = column;
                        if (block.startLine != block.endLine) {
                            result.addBlockLine(block);
                        }
                    }
                    break;
                }
                case LINE_COMMENT:
                case LONG_COMMENT:
                    result.addIfNeeded(line, column, EditorColorScheme.COMMENT);
                    break;
                default:
                    if (token == Tokens.LBRACK || (token == Tokens.RBRACK && previous == Tokens.LBRACK)) {
                        result.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                        break;
                    }
                    classNamePrevious = false;
                    result.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
            }
            first = false;
            helper.update(thisLength);
            line = helper.getLine();
            column = helper.getColumn();
            if (token != Tokens.WHITESPACE && token != Tokens.NEWLINE) {
                previous = token;
            }
        }
        if (stack.isEmpty()) {
            if (currSwitch > maxSwitch) {
                maxSwitch = currSwitch;
            }
        }
        //identifiers.finish();
        result.determine(line);
        //result.setExtra(identifiers);
        result.setSuppressSwitch(maxSwitch + 10);
        result.setNavigation(labels);
    }

}