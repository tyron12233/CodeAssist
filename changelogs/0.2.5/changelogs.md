# CodeAssist V0.2.5 ALPHA

## Smart Insert Handlers
Selecting a completion item will now adjust the cursor position accordingly.

### Java
Selecting a method completion item will automatically insert semicolon if the completing method returns `void`
![screen-20220206-200453_2](https://user-images.githubusercontent.com/74818961/152680209-dbf5231e-4a73-4539-bdb3-60f0eda44480.gif)

If the completing method has parameters, the cursor position will be moved inside the parentheses.
![screen-20220206-201355_2](https://user-images.githubusercontent.com/74818961/152680453-bd00ee32-c684-4c67-9565-99b6f0230d21.gif)

For keywords, space will be automatically appended if typing on a blank line.

### XML
Selecting an attribute will automatically place the cursor in between the quote (") characters.
Selecting a value from the completion list will automatically place the cursor on the next line if its not a `FLAG` attribute.
![screen-20220206-202049_2](https://user-images.githubusercontent.com/74818961/152680632-32650a7b-788f-4f38-b8be-8868b3c97b60.gif)

## Variable Name Suggestion
For java variables, CodeAssist now suggests names based on the type.

## Improved Override Inherited Methods Dialog
Methods will now appear as a tree with the class methods as the leaf, arranged at from the direct super class until it reaches `java.lang.Object`

## Adding custom repository URL
Custom repositories can now be added through the repositories.xml file located at the `app` module. The json format is an array of objects with keys of `url` and `name`.
The `url` key specifies the url that the dependency resolver will look for dependencies. The `name` key specifies the name of the directory CodeAssist will use to store caches.
Example contents of `repositories.json`
```json
[
	{
		"name": "maven",
		"url": "https://repo1.maven.org/maven2"
	}
]
```

## Zip Align
APKs are now aligned with `zipalign` before signing.

## Fix files becoming blank
To prevent this issue, the code editor will no longer save files if there is an error with reading them. There will be a snackbar to inform users.
![Screenshot_20220206-154414_CodeAssist](https://user-images.githubusercontent.com/74818961/152680963-09deab8a-39be-43fa-b4cc-eeb8fae8039c.png)

## Auto deletion of symbol pairs
Matching symbol pairs such as `()`, `[]` and `""` will be deleted if their left pair has been deleted.
![screen-20220206-204345_2](https://user-images.githubusercontent.com/74818961/152681435-d8eb2a7a-d7b8-4a34-86aa-5e72860a4f14.gif)

## Ignored end pair symbols
If the current character at the cursor position matches one of the right character from the symbol pairs, the insertion will be ignored and the cursor will go over the character.

![screen-20220206-204705_2](https://user-images.githubusercontent.com/74818961/152681549-baadb906-08c9-4dff-a930-7e0da1edb350.gif)


## Miscellaneous Changes
- Updated editor library to 0.9.2
- Text menus now uses the Action API.
- Moved all file operations to background thread.
- Fixed introduce local variable action not working inside a lambda.
- Fixed crash and freezing when long pressing on a java code with no cache available.
- Fixed some classes does not appear on xml tag completion.
- Fixed flickering of the completion list when invoked multiple times.
- Fixed crash when creating a file that already exists.
