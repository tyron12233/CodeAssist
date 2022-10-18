### CodeAssist v0.2.8 ALPHA

### TextMate Support
- The old JSON based themes are now no longer supported in favor of TextMate themes.
TextMate provides more ways of colorizing language tokens through different scopes. You can find 
  examples of TextMate schemes through the assets folder of the app.

### Drag To Open Support
- When long pressing to show the popup menu on the editor, you can now keep long pressing and release
your finger under the action you want to perform.

### Generating Resource Classes
- CodeAssist will now generate IDs and other resources in real-time as you type through the editor. (R.java)

### Keyboard Suggestions
- Keyboard Suggestions (The text strip that appears above the keyboard) option is now removed.

### Introduce Local Variables
- The action will now work even on erroneous types.

### IDE Logs
- IDE Logs next to the diagnostics tab, will display errors during completion process to better 
debug what has happened before the completion request.
  
### Bug fixes
- Fixed crash when overriding an inherited method with syntax errors
- Fixed file editors getting cleared when indexing the project.
- Fixed crash when calling root() with more than two class roots.
- Fixed margin layout params not suggested on XML completions.
- File manager tree state will now be saved when refreshing.
