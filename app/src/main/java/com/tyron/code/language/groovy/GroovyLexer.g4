/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
lexer grammar GroovyLexer;

@header {
    import java.util.ArrayDeque;
    import java.util.Arrays;
    import java.util.Deque;
    import java.util.Set;
    import java.util.HashSet;
}

@members {
    private static final Set<Integer> ALLOWED_OP_SET = new HashSet<Integer>(Arrays.asList(COMMA, NOT, BNOT, PLUS, ASSIGN, PLUS_ASSIGN, LT, GT, LTE, GTE, EQUAL, UNEQUAL, FIND, MATCH, DOT, SAFE_DOT, STAR_DOT, ATTR_DOT, MEMBER_POINTER, ELVIS, QUESTION, COLON, AND, OR, KW_ASSERT, KW_RETURN)); // the allowed ops before slashy string. e.g. p1=/ab/; p2=~/ab/; p3=!/ab/
    private static enum Brace {
       ROUND,
       SQUARE,
       CURVE
    };
    private Deque<Brace> braceStack = new ArrayDeque<Brace>();
    private Brace topBrace = null;
    private int lastTokenType = 0;
    private long tokenIndex = 0;
    private long tlePos = 0;

    @Override
    public void emit(Token token) {
        tokenIndex++;
        int tokenType = token.getType();
        if (NL != tokenType) { // newline should be ignored
            lastTokenType = tokenType;
        }

        //System.out.println("EM: " + tokenNames[lastTokenType != -1 ? lastTokenType : 0] + ": " + lastTokenType + " TLE = " + (tlePos == tokenIndex) + " " + tlePos + "/" + tokenIndex + " " + token.getText());
        if (tokenType == ROLLBACK_ONE) {
            this.rollbackOneChar();
        }

        super.emit(token);
    }

    // just a hook, which will be overrided by GroovyLangLexer
    protected void rollbackOneChar() {}

    private void pushBrace(Brace b) {
        braceStack.push(b);
        topBrace = braceStack.peekFirst();
        //System.out.println("> " + topBrace);
    }

    private void popBrace() {
        braceStack.pop();
        topBrace = braceStack.peekFirst();
        //System.out.println("> " + topBrace);
    }


    private boolean isSlashyStringAllowed() {
        //System.out.println("SP: " + " TLECheck = " + (tlePos == tokenIndex) + " " + tlePos + "/" + tokenIndex);
        boolean isLastTokenOp = ALLOWED_OP_SET.contains(Integer.valueOf(lastTokenType));
        boolean res = isLastTokenOp || tlePos == tokenIndex;
        //System.out.println("SP: " + tokenNames[lastTokenType] + ": " + lastTokenType + " res " + res + (res ? ( isLastTokenOp ? " op" : " tle") : ""));
        return res;
    }

}



LINE_COMMENT: '//' .*?  ('\n' | EOF)                           -> type(NL) ;
DOC_COMMENT: '/**' .*? '*/'                                    -> type(NL) ;
BLOCK_COMMENT: '/*' .*? '*/'                                   -> type(NL) ;
SHEBANG_COMMENT: { tokenIndex == 0 }? '#!' .*? '\n'            -> skip     ;

WS: [ \t]+ -> skip ;

LPAREN : '(' { pushBrace(Brace.ROUND); tlePos = tokenIndex + 1; } -> pushMode(DEFAULT_MODE) ;
RPAREN : ')' { popBrace(); } -> popMode ;
LBRACK : '[' { pushBrace(Brace.SQUARE); tlePos = tokenIndex + 1; } -> pushMode(DEFAULT_MODE) ;
RBRACK : ']' { popBrace(); } -> popMode ;
LCURVE : '{' { pushBrace(Brace.CURVE); tlePos = tokenIndex + 1; } -> pushMode(DEFAULT_MODE) ;
RCURVE : '}' { popBrace(); } -> popMode ;


MULTILINE_STRING:   (TSQ TSQ_STRING_ELEMENT*? TSQ
                    | TDQ TDQ_STRING_ELEMENT*? TDQ
                    )  -> type(STRING)
                    ;

MULTILINE_GSTRING_START : TDQ TDQ_STRING_ELEMENT*? '$'  -> type(GSTRING_START), pushMode(TRIPLE_QUOTED_GSTRING_MODE), pushMode(GSTRING_TYPE_SELECTOR_MODE);


STRING:   '"' DQ_STRING_ELEMENT*? '"'
        | '\'' SQ_STRING_ELEMENT*? '\''
        ;
SLASHY_STRING: '/' { isSlashyStringAllowed() }? SLASHY_STRING_ELEMENT+? '/' -> type(STRING) ;
DOLLAR_SLASHY_STRING: LDS { isSlashyStringAllowed() }? DOLLAR_SLASHY_STRING_ELEMENT*? RDS -> type(STRING) ;

GSTRING_START: '"' DQ_STRING_ELEMENT*? '$' -> pushMode(DOUBLE_QUOTED_GSTRING_MODE), pushMode(GSTRING_TYPE_SELECTOR_MODE) ;
SLASHY_GSTRING_START: '/' { isSlashyStringAllowed() }? SLASHY_STRING_ELEMENT*? '$' -> type(GSTRING_START), pushMode(SLASHY_GSTRING_MODE), pushMode(GSTRING_TYPE_SELECTOR_MODE) ;
DOLLAR_SLASHY_GSTRING_START: LDS { isSlashyStringAllowed() }? DOLLAR_SLASHY_STRING_ELEMENT*? '$' -> type(GSTRING_START), pushMode(DOLLAR_SLASHY_GSTRING_MODE), pushMode(GSTRING_TYPE_SELECTOR_MODE) ;


fragment SLASHY_STRING_ELEMENT:         SLASHY_ESCAPE
                                       | '$' { !GrammarPredicates.isFollowedByJavaLetterInGString(_input) }?
                                       | ~('/' | '$' | '\u0000' | '\n')
                                       ;
fragment DOLLAR_SLASHY_STRING_ELEMENT: (SLASHY_ESCAPE
                                       | '/' { _input.LA(1) != '$' }?
                                       | '$' { !GrammarPredicates.isFollowedByJavaLetterInGString(_input) }?
                                       | ~('/' | '$')
                                       )
                                       ;
fragment TSQ_STRING_ELEMENT: (ESC_SEQUENCE | DOLLAR_ESCAPE | JOIN_LINE_ESCAPE
                             |  '\'' { !(_input.LA(1) == '\'' && _input.LA(2) == '\'') }?
                             | ~('\'' | '\\')
                             )
                             ;
fragment SQ_STRING_ELEMENT:   ESC_SEQUENCE | DOLLAR_ESCAPE | JOIN_LINE_ESCAPE
                             | ~('\'' | '\\')
                             ;
fragment TDQ_STRING_ELEMENT: (ESC_SEQUENCE | DOLLAR_ESCAPE | JOIN_LINE_ESCAPE
                             |  '"' { !(_input.LA(1) == '"' && _input.LA(2) == '"') }?
                             | ~('"'  | '\\' | '$')
                             )
                             ;
fragment DQ_STRING_ELEMENT:   ESC_SEQUENCE | DOLLAR_ESCAPE | JOIN_LINE_ESCAPE
                             | ~('"'  | '\\' | '$')
                             ;

fragment TSQ: '\'\'\'';
fragment TDQ: '"""';
fragment LDS: '$/';
fragment RDS: '/$';

fragment IDENTIFIER_IN_GSTRING: JavaLetterInGString JavaLetterOrDigitInGString*;

mode TRIPLE_QUOTED_GSTRING_MODE ;
    MULTILINE_GSTRING_END: TDQ -> type(GSTRING_END), popMode ;
    MULTILINE_GSTRING_PART: '$' -> type(GSTRING_PART), pushMode(GSTRING_TYPE_SELECTOR_MODE) ;
    MULTILINE_GSTRING_ELEMENT: TDQ_STRING_ELEMENT  -> more ;

mode DOUBLE_QUOTED_GSTRING_MODE ;
    GSTRING_END: '"' -> popMode ;
    GSTRING_PART: '$' -> pushMode(GSTRING_TYPE_SELECTOR_MODE) ;
    GSTRING_ELEMENT: DQ_STRING_ELEMENT -> more ;

mode SLASHY_GSTRING_MODE ;
    SLASHY_GSTRING_END: ('$'? '/') -> type(GSTRING_END), popMode ;
    SLASHY_GSTRING_PART: '$' -> type(GSTRING_PART), pushMode(GSTRING_TYPE_SELECTOR_MODE) ;
    SLASHY_GSTRING_ELEMENT: SLASHY_STRING_ELEMENT -> more ;

mode DOLLAR_SLASHY_GSTRING_MODE;
    DOLLAR_SLASHY_GSTRING_END: RDS -> type(GSTRING_END), popMode ;
    DOLLAR_SLASHY_GSTRING_PART: '$' -> type(GSTRING_PART), pushMode(GSTRING_TYPE_SELECTOR_MODE) ;
    DOLLAR_SLASHY_GSTRING_ELEMENT: DOLLAR_SLASHY_STRING_ELEMENT -> more ;

mode GSTRING_TYPE_SELECTOR_MODE ; // We drop here after exiting curved brace?
    GSTRING_BRACE_L: '{' { pushBrace(Brace.CURVE); tlePos = tokenIndex + 1; } -> type(LCURVE), popMode, pushMode(DEFAULT_MODE) ;
    GSTRING_ID: IDENTIFIER_IN_GSTRING -> type(IDENTIFIER), popMode, pushMode(GSTRING_PATH) ;

mode GSTRING_PATH ;
    GSTRING_PATH_PART: '.' IDENTIFIER_IN_GSTRING ;
    ROLLBACK_ONE: . -> popMode, channel(HIDDEN) ; // This magic is for exit this state if

mode DEFAULT_MODE ;

fragment SLASHY_ESCAPE: '\\' '/' ;
fragment DOLLAR_ESCAPE: '\\' '$' ;
fragment JOIN_LINE_ESCAPE: '\\' '\r'? '\n';
fragment ESC_SEQUENCE: '\\' [btnfr"'\\] | OCTAL_ESC_SEQ | UNICODE_ESCAPE;
fragment OCTAL_ESC_SEQ: '\\' [0-3]? ZERO_TO_SEVEN? ZERO_TO_SEVEN ;
fragment UNICODE_ESCAPE: '\\' 'u' HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT;
// Numbers
DECIMAL: (DIGITS ('.' DIGITS EXP_PART? | EXP_PART) DECIMAL_TYPE_MODIFIER? ) | DIGITS DECIMAL_ONLY_TYPE_MODIFIER ;
INTEGER: (('0x' | '0X') HEX_DIGITS | ('0b' | '0B') BIN_DIGITS | '0' OCT_DIGITS | DEC_DIGITS) INTEGER_TYPE_MODIFIER? ;
fragment DIGITS: DIGIT | DIGIT (DIGIT | '_')* DIGIT ;
fragment DEC_DIGITS: DIGIT | [1-9] (DIGIT | '_')* DIGIT ;
fragment OCT_DIGITS: ZERO_TO_SEVEN | ZERO_TO_SEVEN (ZERO_TO_SEVEN | '_')* ZERO_TO_SEVEN ;
fragment ZERO_TO_SEVEN: [0-7];
fragment HEX_DIGITS: HEX_DIGIT | HEX_DIGIT (HEX_DIGIT | '_')* HEX_DIGIT ;
fragment HEX_DIGIT : [0-9a-fA-F];
fragment BIN_DIGITS: BIN_DIGIT | BIN_DIGIT (BIN_DIGIT | '_')* BIN_DIGIT ;
fragment BIN_DIGIT : [01];
fragment SIGN: ('-'|'+') ;
fragment EXP_PART: ([eE] SIGN? DIGIT+) ;
fragment DIGIT: [0-9];
fragment INTEGER_TYPE_MODIFIER: ('G' | 'L' | 'I' | 'g' | 'l' | 'i') ;
fragment DECIMAL_TYPE_MODIFIER: ('G' | 'D' | 'F' | 'g' | 'd' | 'f') ;
fragment DECIMAL_ONLY_TYPE_MODIFIER: ( 'D' | 'F' | 'd' | 'f') ;
KW_CLASS: 'class' ;
KW_INTERFACE: 'interface' ;
KW_TRAIT: 'trait' ;
KW_ENUM: 'enum' ;
KW_PACKAGE: 'package' ;
KW_IMPORT: 'import' ;
KW_EXTENDS: 'extends' ;
KW_IMPLEMENTS: 'implements' ;
KW_DEF: 'def' ;
KW_NULL: 'null' ;
KW_TRUE: 'true' ;
KW_FALSE: 'false' ;
KW_NEW: 'new' ;
KW_SUPER: 'super' ;
KW_THIS: 'this' ;
KW_IN: 'in' ;
KW_FOR: 'for' ;
KW_IF: 'if' ;
KW_ELSE: 'else' ;
KW_DO: 'do' ;
KW_WHILE: 'while' ;
KW_SWITCH: 'switch' ;
KW_CASE: 'case' ;
KW_DEFAULT: 'default' ;
KW_CONTINUE: 'continue' ;
KW_BREAK: 'break' ;
KW_RETURN: 'return' ;
KW_TRY: 'try' ;
KW_CATCH: 'catch' ;
KW_FINALLY: 'finally' ;
KW_THROW: 'throw' ;
KW_THROWS: 'throws' ;
KW_ASSERT: 'assert' ;
KW_CONST:   'const';
KW_GOTO: 'goto';
RUSHIFT_ASSIGN: '>>>=' ;
RSHIFT_ASSIGN: '>>=' ;
LSHIFT_ASSIGN: '<<=' ;
//RUSHIFT: '>>>' ;
SPACESHIP: '<=>' ;
ELVIS: '?:' ;
SAFE_DOT: '?.' ;
STAR_DOT: '*.' ;
ATTR_DOT: '.@' ;
MEMBER_POINTER: '.&' ;
LTE: '<=' ;
GTE: '>=' ;
CLOSURE_ARG_SEPARATOR: '->' ;
DECREMENT: '--' ;
INCREMENT: '++' ;
POWER: '**' ;
LSHIFT: '<<' ;
//RSHIFT: '>>' ;
RANGE: '..' ;
ORANGE: '..<' ;
EQUAL: '==' ;
UNEQUAL: '!=' ;
MATCH: '==~' ;
FIND: '=~' ;
AND: '&&' ;
OR: '||' ;
PLUS_ASSIGN: '+=' ;
MINUS_ASSIGN: '-=' ;
MULT_ASSIGN: '*=' ;
DIV_ASSIGN: '/=' ;
MOD_ASSIGN: '%=' ;
BAND_ASSIGN: '&=' ;
XOR_ASSIGN: '^=' ;
BOR_ASSIGN: '|=' ;
SEMICOLON: ';' ;
DOT: '.' ;
COMMA: ',' ;
AT: '@' ;
ASSIGN: '=' ;
LT: '<' ;
GT: '>' ;
COLON: ':' ;
BOR: '|' ;
NOT: '!' ;
BNOT: '~' ;
MULT: '*' ;
DIV: '/' ;
MOD: '%' ;
PLUS: '+' ;
MINUS: '-' ;
BAND: '&' ;
XOR: '^' ;
QUESTION: '?' ;
ELLIPSIS: '...';
KW_AS: 'as' ;
KW_INSTANCEOF: 'instanceof' ;
BUILT_IN_TYPE: 'void' | 'boolean' | 'byte' | 'char' | 'short' | 'int' | 'float' | 'long' | 'double';
// Modifiers
VISIBILITY_MODIFIER: (KW_PUBLIC | KW_PROTECTED | KW_PRIVATE) ;
fragment KW_PUBLIC: 'public' ;
fragment KW_PROTECTED: 'protected' ;
fragment KW_PRIVATE: 'private' ;
KW_ABSTRACT: 'abstract' ;
KW_STATIC: 'static' ;
KW_FINAL: 'final' ; // Class
KW_TRANSIENT: 'transient' ; // methods and fields
KW_NATIVE: 'native' ; // Methods and fields, as fields are accesors in Groovy.
KW_VOLATILE: 'volatile' ; // Fields only
KW_SYNCHRONIZED: 'synchronized' ; // Methods and fields.
KW_STRICTFP: 'strictfp';
KW_THREADSAFE: 'threadsafe';
/** Nested newline within a (..) or [..] are ignored. */
IGNORE_NEWLINE : '\r'? '\n' { topBrace == Brace.ROUND || topBrace == Brace.SQUARE }? -> skip ;
// Match both UNIX and Windows newlines
NL: '\r'? '\n';
IDENTIFIER: JavaLetter JavaLetterOrDigit*; // reference https://github.com/antlr/grammars-v4/blob/master/java8/Java8.g4
fragment
JavaLetter
	:	[a-zA-Z$_] // these are the "java letters" below 0x7F
	|	JavaUnicodeChar
	;
fragment
JavaLetterOrDigit
	:	[a-zA-Z0-9$_] // these are the "java letters or digits" below 0x7F
	|	JavaUnicodeChar
	;
fragment
JavaLetterInGString
	:	[a-zA-Z_]
	|	JavaUnicodeChar
	;
fragment
JavaLetterOrDigitInGString
	:	[a-zA-Z0-9_]
	|   JavaUnicodeChar
	;
fragment
JavaUnicodeChar
	:	// covers all characters above 0x7F which are not a surrogate
        ~[\u0000-\u007F\uD800-\uDBFF]
        {Character.isJavaIdentifierPart(_input.LA(-1))}?
    |	// covers UTF-16 surrogate pairs encodings for U+10000 to U+10FFFF
        [\uD800-\uDBFF] [\uDC00-\uDFFF]
        {Character.isJavaIdentifierPart(Character.toCodePoint((char)_input.LA(-2), (char)_input.LA(-1)))}?
    ;