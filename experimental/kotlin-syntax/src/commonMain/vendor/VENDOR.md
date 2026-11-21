# Vendored: the Kotlin compiler's multiplatform parser

These files are copied from the Kotlin compiler, and their package names are left alone
(`org.jetbrains.kotlin.kmp.*`) so that a re-sync is an overwrite, not a merge. Every file is upstream's
byte for byte at the commit below except three: `org/jetbrains/kotlin/kmp/tree/LightSyntaxTree.kt`, which
carries local additions that a re-sync must re-apply, and the two parser files that carry later upstream
fixes backported ahead of a re-sync; see [Local modifications](#local-modifications).

    upstream:  https://github.com/JetBrains/kotlin
    path:      compiler/multiplatform-parsing/common/src
    commit:    04f973de2735fd71c35700a7757364fbb8c6ca3e
    dated:     2026-09-15
    licence:   Apache 2.0 (LICENSE.txt in this directory)

## Why vendored rather than depended on

`:compiler:multiplatform-parsing` is an internal module of the Kotlin build. It is not published to Maven,
so there is no coordinate to declare. Its own dependencies ARE published, and both carry the Apple targets
this module needs: `org.jetbrains:syntax-api` and `org.jetbrains:annotations`.

Upstream declares only `jvm` and `wasmJs` targets. That is a build-file choice, not a source constraint —
the sources are `commonMain` over the stdlib and `syntax-api` — so this module declares the iOS targets
instead. If a dependency ever stops publishing them, that is what breaks, and it breaks loudly at resolution.

## Local modifications

The grammar is upstream's: no parser, lexer or element-type file carries a change of ours, so this is still
the compiler's own parse. The value of the parser is that it IS the compiler's grammar, and a local fix to it
would quietly forfeit exactly that; anything that changes what parses, or how, belongs upstream.

### Backported upstream grammar fixes

Two parser files are ahead of the commit above, by fixes copied from a LATER upstream revision (master at
`04f3c969a5c61163f576fa39072982c05fb55f7c`, 2026-11-20) and nothing else. Each made the editor report a
syntax error on code the compiler accepts. The rest of that revision's parser changes are not taken; a full
re-sync brings them all.

- **KT-90707**, enum entries named like a soft modifier (`error("error")`, `value {}`, `inline("inline")`):
  `KotlinParsing.parseEnumEntry` reads its modifier list with `NO_MODIFIERS_BEFORE_FOR_ENUM_ENTRY`, which adds
  `LPAR` and `LBRACE` to the old `COMMA_SEMICOLON_RBRACE_SET`, so the name is not taken for a modifier.
- **KT-90782**, a call named like a modifier on the line after a local or script top-level class
  (`class C` then `error("boom")`): `parseClassOrObject` takes the `DeclarationParsingMode`, threaded through
  `parseClass`, `parseObject` and `parseCommonDeclaration`, and outside `MEMBER_OR_TOPLEVEL` ends the class at
  modifiers that start a new line and are not followed by `constructor`. `parseObjectLiteral` in
  `KotlinExpressionParsing.kt` passes `MEMBER_OR_TOPLEVEL` to keep its old behaviour.

`ModifierNamedCallParseTest` pins both. A re-sync to any revision at or after the one named above supersedes
them, and the overwrite takes them out with nothing to re-apply.

One file is modified, `org/jetbrains/kotlin/kmp/tree/LightSyntaxTree.kt`, and only by additions to the tree
class (no upstream line changes behaviour). They live here rather than in the `dev.ide.kotlin.syntax`
sources beside it because they read the tree's private arrays, which nothing outside the class can see:

- **Index-based child access** (since `57cbb7dc3`): `parentIndex`, `childCount`, `childIndexAt` and
  `childIndexByType` answer by node index without boxing a `LightNode` per child. Used by the `Kt*` facade
  (`KtTree.kt`, `KtElement.kt`) and `KotlinSyntax.kt`, which walk the tree on every edit. To serve them,
  `ChildrenList.indices` is no longer private; the class itself still is, so that is visible in this file
  only.
- **Incremental reparse** (since `bafb831fc`): `withReplacedSubtree` builds the tree for a new text by
  splicing a re-parsed body into this one, with its helpers `remapped` and `edgeToken` and the
  `ArrayTokenList` token list it assembles. Used by `KotlinSyntax.reparseFileLazily`, which decides when a
  splice gives the same tree a fresh parse would (`LazyBlockParityTest` pins that it does).
- **Same-shape reparse**: `sameShapeWith`, called from `withReplacedSubtree` when the new body has the same
  composites and as many tokens as the old one (a letter typed into a name). The new tree then shares the
  old one's structural arrays, and `ArrayTokenList.types` is readable so it can share the token types too.

If a later revision ships any of these upstream, or an equivalent, drop the local copy rather than keep two.

## Re-syncing

The overwrite below deletes the local modifications, and the build then fails until they are back
(`KotlinSyntax.kt` and the facade call them). Save them first, as a patch against the commit in this
repository that last brought in upstream's file unmodified (`10890e42c`, the original import). From this
directory:

    git diff 10890e42c -- org/jetbrains/kotlin/kmp/tree/LightSyntaxTree.kt > local-tree.patch

then overwrite:

    git clone --filter=blob:none --sparse --depth 1 https://github.com/JetBrains/kotlin.git
    cd kotlin && git sparse-checkout set compiler/multiplatform-parsing
    cp -R compiler/multiplatform-parsing/common/src/. <this directory>/
    # drop the JFlex inputs; the generated lexers are already committed upstream
    find <this directory> -name '*.flex' -o -name '*.skeleton' | xargs rm -f

Then re-apply the local modifications (`git apply -3 local-tree.patch` from this directory, resolving any
conflict by hand against the new upstream file), update the upstream commit above and the import commit the
patch is taken against, bump `syntax-api` to whatever the new revision's `gradle/libs.versions.toml` pins, and
run `:kotlin-syntax:check`. The parity suite is what tells you whether the new revision still
agrees with the compiler we pin.
