# CodeAssist V0.2.6 ALPHA

## Theme Overhaul
- The app no longer uses hardcoded colors and is now using proper styles from Material 3.
- Added support for dark and light theme, can be manually changed on the app's settings.

## User-defined editor color scheme
- The editor color scheme can be manually changed on the editor settings.
Note: This is for the `editor` color scheme only. Custom theming for the whole app is planned and a live editor color scheme editor is also planned.

Sample color schme (from CodeAssist's dark editor scheme)
```json
{
    "name": "CodeAssist Dark",
    "colors": {
        "keyword": "#CC7832",
        "annotation": "#BBB529",
        "literal": "#629755",
        "comment": "#808080",
        "operator": "#FAFAFA",
        "functionName": "#FFC66D",
        "attributeName": "#9876AA",
        "attributeValue": "#629755",
        "htmlTag": "#E8BF6A",
        "identifierName": "#FAFAFA",
        "textNormal": "#FAFAFA",
        
        "blockLineColor": "#555555",
        
        "lineNumberPanelText": "#555555",
        "lineDivider": "#555555",
        "completionPanelStrokeColor": "#555555",
        
        "lineNumberBackground": "#1F1A1B",
        "completionPanelBackground": "#1F1A1B",
        
        "wholeBackground": "#1F1A1B"
        
    }
}
```

## Editor Crashes
- The app will now try to save the current opened files in the editor if it has encountered an unknown error.

## About Page
- Improved about page design to better suit the new theme.
- Added license card.

## Miscellaneous Changes
- Fixed editor `IllegalStateException` crash.
- Fixed crash when pressing `Preview Layout` on rare occasions.
- Fixed missing keywords on java completions.
- Fixed crash when `CompileTask#root()` returns null
- Fixed primitive types getting imported on implement abstract methods action.
- Removed toast when copying text.
- Added an option in the editor settings to quickly delete an empty line.
- The bottom sheet from the editor can now be half expanded.
- Symbol input view no longer obstructs the editor.
- Language dependendent tab widths.
- Background analyzers are no longer run when the app is compiling to save resources.
- Added auto indent for JSON Arrays.
- Text lines are now trimmed before pasting.
