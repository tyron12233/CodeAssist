# CodeAssist
A Code Completion engine based on javac APIs.

## Building - Android Studio
Clone this repository to your local device and then open it on Android Studio.

## Building - APK Builder
APK Builder is an app I have developed for building APK files on android more on it [here](https://github.com/tyron12233/APKBuilder)

The steps of building using APK Builder are:
 - Download the sources as zip file
 - Extract them to your storage
 - Select the appropriate folders in APK Builder:

   | Folder                             | Path type      |
   |------------------------------------|----------------|
   | `app/src/main/res`                 | Resources path |
   | `app/src/main/java`                | Java path      |
   | `app/src/main/AndroidManifest.xml` | Manifest path  |
   | `app/libs-apkbuilder`              | Libraries      |

### Notes
- **The libraries in `app/lib-apkbuilder` are for use with APK Builder only**
- **Make sure you disable kotlin option before running.**

## Contributing
- Pull request must have a short description as a title and a more detailed one in the description
- Feature additions must include Unit/Instrumentation tests. This is for future stability of the app and modules.

# Special thanks
- Rosemoe/CodeEditor 
- JavaNIDE
- Mike Anderson
- Java Language Server
