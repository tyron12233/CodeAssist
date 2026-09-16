# Vendored: the Kotlin compiler's multiplatform parser

These files are copied **verbatim** from the Kotlin compiler, and their package names are left alone
(`org.jetbrains.kotlin.kmp.*`) for exactly that reason: a re-sync is then an overwrite, not a merge.

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

NONE. Every file is byte-identical to upstream. That is deliberate and worth defending: the value of this
parser is that it IS the compiler's grammar, and a local fix would quietly forfeit exactly that. Anything
this project needs on top belongs in the `dev.ide.kotlin.syntax` sources beside it — today, the `Kt*`
facade over the tree these files produce.

## Re-syncing

    git clone --filter=blob:none --sparse --depth 1 https://github.com/JetBrains/kotlin.git
    cd kotlin && git sparse-checkout set compiler/multiplatform-parsing
    cp -R compiler/multiplatform-parsing/common/src/. <this directory>/
    # drop the JFlex inputs; the generated lexers are already committed upstream
    find <this directory> -name '*.flex' -o -name '*.skeleton' | xargs rm -f

Then update the commit above, bump `syntax-api` to whatever the new revision's `gradle/libs.versions.toml`
pins, and run `:kotlin-syntax:check`. The parity suite is what tells you whether the new revision still
agrees with the compiler we pin.
