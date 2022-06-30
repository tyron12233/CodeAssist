### CodeAssist v0.2.9 ALPHA

### ViewBinding support 
- Real time code completions and analysis for view binding generated files. To enable the feature, check [this pull request.](https://github.com/tyron12233/CodeAssist/pull/331).
- Data binding is not yet supported, we are looking forward on implementing it in the future.

### Jetpack Compose template
- You can now create jetpack compose projects. Previews are not yet supported.

### Updated Java Compiler
- CodeAssist can now compile libaries that are built with JDK 11. (Like sora-editor).

### Formatter
- Eclipse formatter is now used by default with the standard java convention settings. We are planning to allow configuring code formatting options in the future.

### Importing Files.
- You can now import files by long pressing a directory and pressing `Import`.
- This feature is only available on android 10 devices and below. Scoped Storage support will be added in the future.

### Misc
- File drawer can now be scrolled horizontally.
- Proguard mapping files are now included the apk output.
- Notification icon is now shown properly.
- Project path will no longer be cleared when selecting a new path.
- Fixed wrong block line color parsed from themes.
- Updated BundleTool version.
- Fixed UI freeze when closing an XML tag.
- The name of the library will now be displayed instead of its hash on the build log.
- R.java file is now updated incrementally.
- Added code completion on attributes from the Layout Editor.

### Important changes
- Kotlin code completions and error highlighting are **temporarily** disabled. They will be reimplemented when the new kotlin compiler API is available.
- Code completions are now not available while the project is compiling and indexing.

### XML Completions
- After comppleting an attribute, the completion window will be shown automatically if applicable. See [this pull request](https://github.com/tyron12233/CodeAssist/pull/368)

### What's next
- Ported Gradle APIs. (see `:build-tools:builder-api`) (85%)
- Java completions using the kotlin compiler API. (70%)
