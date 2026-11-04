# Calc: a language served by a language server

A sample CodeAssist plugin that teaches the editor a language through a Language Server Protocol server
(SPI 3.3.0, `lsp-api`). The language is Calc:

```
# the area of a rectangle
let width = 4
let height = width * 2
print max(width, height) * 3
```

The plugin registers three things: the `.calc` file type, a `LanguageServerDefinition`, and the editor's
lexical colouring. Everything else a `.calc` file gets comes from the server through the IDE's LSP client:
errors and warnings, quick fixes (one a plain edit, one a server command that sends `workspace/applyEdit`
back), completion with snippets, parameter info, hover, go to declaration, find usages, highlight usages,
rename, the structure view, folding, Reformat Code, semantic colours and inlay hints.

It also shows a test runner (`CalcTestProvider`, SPI 3.3.0 `TEST_PROVIDER_EP`): a line
`test doubles: width * 2 is 8` is a test, with a run mark in the gutter, a "Calc tests" row in the Run picker,
and its result in the Run screen's Tests tab.

The server (`CalcServer.kt`, with the language itself in `CalcLanguage.kt`) is ordinary Kotlin in the same
APK, started by the plugin as a separate process:

```
/apex/com.android.art/bin/dalvikvm64 -cp <this plugin's base.apk> dev.codeassist.lspdemo.CalcServerMain
```

and spoken to over stdin/stdout. That is the same path a native server takes (the Dart analysis server,
clangd); a native one would instead ship its executable in `jniLibs` and start it from
`PluginRegistration.nativeLibrary`.

## Try it

```
./gradlew :samples:lsp-plugin:assembleDebug
adb install -r samples/lsp-plugin/build/outputs/apk/debug/lsp-plugin-debug.apk
```

Allow the plugin in CodeAssist under Settings and tools > Plugins > Installed, restart, and open or create
a `.calc` file inside a module's source folder (for example `app/src/main/java/`).

`./gradlew :samples:lsp-plugin:testDebugUnitTest` checks the language and the server's protocol on the JVM.
