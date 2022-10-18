/** Taken from "The Definitive ANTLR 4 Reference" by Terence Parr */

// Derived from http://json.org
grammar JSON;

json
   : value
   ;

obj
   : LBRACE pair (',' pair)* RBRACE
   | LBRACE RBRACE
   ;

pair
   : STRING COLON value
   ;

arr
   : LBRACKET value (COMMA value)* RBRACKET
   | LBRACKET RBRACKET
   ;

value
   : STRING
   | NUMBER
   | obj
   | arr
   | TRUE
   | FALSE
   | NULL
   ;

TRUE
   : 'true'
   ;
FALSE
   : 'false'
   ;
NULL
   : 'null'
   ;


COLON
    : ':'
    ;

STRING
   : '"' (ESC | SAFECODEPOINT)* '"'
   ;

LBRACE
   : '{'
   ;

RBRACE
   : '}'
   ;


LBRACKET
   : '['
   ;

RBRACKET
   : ']'
   ;

COMMA
   : ','
   ;


fragment ESC
   : '\\' (["\\/bfnrt] | UNICODE)
   ;
fragment UNICODE
   : 'u' HEX HEX HEX HEX
   ;
fragment HEX
   : [0-9a-fA-F]
   ;
fragment SAFECODEPOINT
   : ~ ["\\\u0000-\u001F]
   ;


NUMBER
   : '-'? INT ('.' [0-9] +)? EXP?
   ;


fragment INT
   : '0' | [1-9] [0-9]*
   ;

// no leading zeros

fragment EXP
   : [Ee] [+\-]? INT
   ;

// \- since - means "range" inside [...]

WS
   : [ \t\n\r] + -> skip
   ;