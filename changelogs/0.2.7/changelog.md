### CodeAssist v0.2.7 ALPHA

### Self closing XML Tags
On an xml file, typing the closing character (>) will now automatically close the current tag closest to the cursor. It is smart, only closing the tags when 
its appropriate.

### Namespace aware XML Completions
- Android xml attributes will now follow the namespace that is defined on the root tag of the xml.
- Added namespace suggestion in the root tag.

### Improved XML Insert Handlers
- Adding a new line in-between a start and closing tag will indent the cursor on the next line and place the end tag at the same level of the start tag.

### Resource types code completion
Resources found from projects including strings, dimensions will now be included in xml auto complete.
Example: 
typing `@string/` will suggest all the strings that can be found on the project.

#### Current Limitations (Will be adressed in a later version)
- The project needs to be refreshed in orderfor the resource types to update.
- The suggestions contains resource types that are not appropriate to the current attribute. e.g strings are suggested on a theme attribute.
- The current supported resources are strings, styles, styleables, integers, booleans, attributes and colors. Other resources will be added later.
- You need to type the whole resource type in order for the resource names to show up.

### Temporarily removed
- XML Error highlihgtning, it has memory leaks and will be re added once resolved.
- Layout editor attribute value code completion. It is being reworked and will be added again once stable.

### Better Completion Sorting
- The completion items now have three levels of sorting: by how similar it is to the current prefix, by its category and by its name.

### Save button
- There is now a button to force CodeAssist to save the opened files to disk. Its located at the folder icon on the toolbar next to the refresh option.
- Note: Files are still automatically saved when you switch between files, compile the project, leave the project, and rotate the device.

### Indicator when file is not saved to disk.
- If the file is not yet saved to disk, its name on the toolbar will be prefixed with `*`. (\*MyFileName.java)

### Blank files
- If the project has failed to open and the user tries to open a file, it will not be read properly and causes the file contents to be erased. It is now solved on this version.

### Uncaught excpetions thrown by actions
- Exceptions thrown by actions from the Actions API will now be caught by the action system and will display a dialog or crash if necessary.

### Case insensitive matching
- There is now an option to match class names with lower case prefix. It can be enabled on the editor settings.

### Long running tasks
- If an action performs a task for more than two seconds, the [progress system](https://github.com/tyron12233/CodeAssist/blob/44b91e51a200ccc29e8097960e04a92bab8d998a/completion-api/src/main/java/com/tyron/completion/progress/ProgressManager.java#L81) will now show a progress bar indicating that a task is taking too long to complete.

### Bug Fixes
- Fix views being drawn under the status bar on older devices.
- Fix crash when overriding inheritted methods and the cursor position is unknown.

### Miscellaneous 
- Pasting a text will now follow the language's indent (currently for java only)
- Depreecated method underlines will now mark only the method names except for the whole method body.
- Files that are not text will no longer be allowed to be opened on the editor.
- Symbols defined in the top level class will now be included in completion list.
- Cursor position will now be restored to where it was last located when switching between files.
