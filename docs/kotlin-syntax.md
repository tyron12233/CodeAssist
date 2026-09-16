# kotlin-syntax (experimental)

A portable Kotlin lexer and parser that mirrors the compiler's own token vocabulary, element vocabulary and
PSI shape. Nothing depends on it. It is built and tested by CI and imported by no other module.

## Why

The editor backend (`:lang-kotlin`) uses the Kotlin compiler for exactly one thing: parsing. It then walks the
resulting PSI directly — 130 distinct `Kt*` types over ~3,000 references — and does its own symbols,
inference, completion and diagnostics on top.

That parse is the single thing in the language stack with no Kotlin/Native path, so it is what pins Kotlin
completion and error checking to the JVM. Measured on the current tree:

| | lines |
|---|---|
| `:lang-kotlin` analysis code (excluding `interp/`, `compile/`) | 25,166 |
| of which deeply PSI-coupled (10+ references) | 18,274 |
| the neutral DOM those lines were meant to use | 81, and effectively unused |

Rewriting the grammar, or adopting a third-party one (tree-sitter, ANTLR), means owning a tree whose shape
differs from the one those 18,274 lines assume. Every divergence then surfaces as a wrong diagnostic under
correct code, which is the failure mode users punish hardest.

So this module mirrors the compiler's model instead. The compiler's own parser is a good target for that: its
`parsing` and `lexer` packages reference only **32 distinct IntelliJ types**, and five carry the weight —
`IElementType`, `TokenSet`, `PsiBuilder`, `WhitespacesAndCommentsBinder`, and the output tree. Reproduce those
and the grammar becomes a translation rather than a redesign.

## What is here

```
tree/       IElementType, TokenSet, AstNode        mirrors com.intellij.psi.tree / com.intellij.lang
lexer/      KtTokens, KotlinLexer                   mirrors org.jetbrains.kotlin.lexer
KtNodeTypes.kt                                      mirrors org.jetbrains.kotlin.KtNodeTypes
parsing/    SyntaxTreeBuilder + impl, KotlinParsing, KotlinExpressionParsing, KotlinParser
psi/        Kt* facade + psiUtil helpers            mirrors org.jetbrains.kotlin.psi
```

The module is multiplatform (jvm + iosSimulatorArm64 + iosArm64) with **no dependencies at all**, deliberately.
Whether `commonMain` is portable is unanswerable until a non-JVM target actually compiles it, so the iOS
targets are declared from the first commit rather than added later.

## How it is checked

Hand-written cases say what the behaviour should be. They are not sufficient on their own: a lexer can pass any
number of them and still disagree with the compiler on the input that matters. So the suite also runs both
implementations over code nobody wrote for the test.

- `KotlinLexerTest`, `SyntaxTreeBuilderTest`, `KotlinParserTest`, `KtPsiFacadeTest` — 102 tests, and they run
  on **both** the JVM and the iOS simulator.
- `KotlinLexerOracleTest` (JVM only) — our lexer against `org.jetbrains.kotlin.lexer.KotlinLexer`, token for
  token and offset for offset, including a sweep over **every `.kt` file in this repository (2,394 files)**.
- `VocabularyParityTest` (JVM only) — reflects over the compiler's `KtTokens` and `KtNodeTypes` and fails if it
  declares a name this module does not. A Kotlin bump that adds syntax shows up here.

Names in both oracles come from FIELD names by reflection, never from `toString()`: the compiler's debug names
are not always its field names, and a comparison built on them would report differences that are only spelling.

Run them with:

```sh
./gradlew :kotlin-syntax:jvmTest :kotlin-syntax:iosSimulatorArm64Test
```

## What the oracle has already caught

Every one of these was a divergence from the compiler that the hand-written tests had passed:

- `?.` and `?:` are `QUEST` + `DOT` / `QUEST` + `COLON` at the lexer level, joined one layer up. A lexer that
  commits to `?.` makes the nullable-type marker unreachable.
- `!!` is two `EXCL`. In prefix position the same two tokens are a double negation, so only the parser can
  join them.
- `!in`, `!is` and `as?` are the opposite case: adjacency is part of their spelling, so the lexer produces them.
- A raw string's parts are cut at every backslash, every line break, and every quote that is not part of the
  closing triple — each emitted one at a time.
- `DANGLING_NEWLINE`, which ends an unterminated string, is **zero width**: the newline stays ordinary
  whitespace so the code after it keeps lexing as code.
- An unterminated backtick is a `BAD_CHARACTER`, not a name running to the end of the line.
- `1e` is a malformed FLOAT, not an integer followed by a name.

## Not done yet

The parser covers mainstream Kotlin and is error-tolerant everywhere, but it is not yet at parity:

- **No tree oracle.** The lexer is diffed against the compiler over the whole repository; the parser is not.
  That is the next thing to build, and it is what would turn "the shapes look right" into a measurement.
- Contracts (`contract { … }` as a parsed effect list), context parameters beyond the modifier, `expect`/`actual`
  specifics, and script (`.kts`) files are unmodelled.
- Whitespace/comment edge binders are the platform default only; the compiler's custom binders are not ported,
  so a doc comment is found by looking backwards rather than by being inside the declaration.
- Incremental reparse (the equivalent of `KotlinPsiMutation.reparse`) does not exist. Every parse is a full one.

See `ios-interpreter-portability` in the project notes for how this fits the wider question of running Kotlin
analysis off the JVM.
