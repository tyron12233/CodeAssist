package com.tyron.completion.java.util;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;

import com.sun.source.tree.LineMap;
import com.sun.tools.javac.parser.Scanner;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens.Token;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.util.Context;
import com.tyron.completion.progress.ProgressManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FileContentFixer {

    /**
     * The injected identifier before completion, this is done so it so there will always be a
     * text at the current position of the caret.
     */
    public static final String INJECTED_IDENT = "CodeAssistRulezzzz";

    private static final Set<TokenKind> VALID_MEMBER_SELECTION_TOKENS =
            ImmutableSet.of(
                    TokenKind.IDENTIFIER,
                    TokenKind.LT,
                    TokenKind.NEW,
                    TokenKind.THIS,
                    TokenKind.SUPER,
                    TokenKind.CLASS,
                    TokenKind.STAR);
    /** Token kinds that can not be right after a memeber selection. */
    private static final Set<TokenKind> INVALID_MEMBER_SELECTION_SUFFIXES =
            ImmutableSet.of(TokenKind.RBRACE);

    private final Context context;

    public FileContentFixer(Context context) {
        this.context = context;
    }

    public CharSequence fixFileContent(CharSequence content) {
        Scanner scanner = ScannerFactory.instance(context).newScanner(content, true);
        List<Insertion> insertions = new ArrayList<>();
        for (; ; scanner.nextToken()) {
            ProgressManager.checkCanceled();

            Token token = scanner.token();
            if (token.kind == TokenKind.EOF) {
                break;
            } else if (token.kind == TokenKind.DOT || token.kind == TokenKind.COLCOL) {
                fixMemberSelection(scanner, insertions);
            } else if (token.kind == TokenKind.ERROR) {
                int errPos = scanner.errPos();
                if (errPos >= 0 && errPos < content.length()) {
                    fixError(scanner, content, insertions);
                }
            }
        }
        return Insertion.applyInsertions(content, insertions);
    }

    private void fixMemberSelection(Scanner scanner, List<Insertion> insertions) {
        Token token = scanner.token();
        Token nextToken = scanner.token(1);

        LineMap lineMap = scanner.getLineMap();
        int tokenLine = (int) lineMap.getLineNumber(token.pos);
        int nextLine = (int) lineMap.getLineNumber(nextToken.pos);

        if (nextLine > tokenLine) {
            // The line ends with a dot. It's likely the user is entering a dot and waiting for member
            // completion. The current line is incomplete and contextually invalid.
            insertions.add(Insertion.create(token.endPos, INJECTED_IDENT + ";"));
        } else if (!VALID_MEMBER_SELECTION_TOKENS.contains(nextToken.kind)) {
            String toInsert = INJECTED_IDENT;
            if (INVALID_MEMBER_SELECTION_SUFFIXES.contains(nextToken.kind)) {
                toInsert = INJECTED_IDENT + ";";
            }

            // The member selection is contextually invalid. Fix it.
            insertions.add(Insertion.create(token.endPos, toInsert));
        }
    }

    private void fixError(Scanner scanner, CharSequence content, List<Insertion> insertions) {
        int errPos = scanner.errPos();
        if (content.charAt(errPos) == '.' && errPos > 0 && content.charAt(errPos) == '.') {
            // The scanner fails at two dots because it expects three dots for
            // ellipse. The errPos is at the second dot.
            //
            // If the second dot is followed by an identifier character, it's likely
            // the user is trying to complete between the two dots. Otherwise, the
            // user is likely in the process of typing the third dot.
            if (errPos < content.length() - 1
                    && Character.isJavaIdentifierStart(content.charAt(errPos + 1))) {
                // Insert a dumbIdent between two dots so the Javac parser can parse it.
                insertions.add(Insertion.create(errPos, INJECTED_IDENT));
            }
        }
    }

    public static class Insertion {
        private static final Ordering<Insertion> REVERSE_INSERTION =
                Ordering.natural().onResultOf(Insertion::getPos).reverse();

        private final int pos;
        private final String text;

        public Insertion(int pos, String text) {
            this.pos = pos;
            this.text = text;
        }

        public int getPos() {
            return pos;
        }

        public String getText() {
            return text;
        }

        public static Insertion create(int pos, String text) {
            return new Insertion(pos, text);
        }

        public static CharSequence applyInsertions(CharSequence content, List<Insertion> insertions) {
            List<Insertion> reverseInsertions = REVERSE_INSERTION.immutableSortedCopy(insertions);

            StringBuilder sb = new StringBuilder(content);

            for (Insertion insertion : reverseInsertions) {
                sb.insert(insertion.getPos(), insertion.getText());
            }
            return sb;
        }
    }
}
