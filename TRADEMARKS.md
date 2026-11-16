# CodeAssist name and logo

The source code of CodeAssist is free software under the GPL (see [LICENSE](LICENSE)). You may use, change
and share it under that license.

The **name "CodeAssist"**, the **CodeAssist logo and app icon**, and the **application id `com.tyron.code`**
are not part of that grant. They identify the builds published by the CodeAssist project, so that people who
install "CodeAssist" know who made what they are running. Section 7(e) of the GPLv3 lets a project keep its
names and marks out of the license, and this file does that.

## If you distribute a modified build

A modified build is any build whose code differs from a tagged release of this repository, including one
with features removed.

You must:

- give it a different name, one that does not contain "CodeAssist" and is not easily confused with it;
- give it a different app icon and logo;
- give it a different application id (not `com.tyron.code`, and not one that imitates it);
- keep the license and copyright notices, and offer your source code, as the GPL requires.

You may:

- say that it is "based on CodeAssist" or "a fork of CodeAssist", in text, with a link to this repository;
- keep internal identifiers (package names, class names, module names) as they are.

You may not:

- present it as the official app, or as endorsed by the CodeAssist project;
- publish it to an app store or a download site under the CodeAssist name or icon.

## What needs no permission

- Sharing the unmodified APKs from the project's GitHub Releases, under their own name.
- Using the name to refer to the project: reviews, tutorials, articles, plugin listings ("a plugin for
  CodeAssist"), and compatibility statements.
- Building from source for your own use, with or without changes.

## The project's online services

The Projects Store, CodeAssist Free AI, push notifications and the other hosted services are run by the
CodeAssist project for its own releases. They are not covered by the source license. Access from modified
builds may be limited or turned off, and a modified build should point at services of its own (every endpoint
is a build property: see `app/ide-android/build.gradle.kts`).

The same goes for ads: the AdMob ids of the official app are not in this repository, and a modified build must
not use them.

## Questions and reports

To ask for permission for a use this page does not cover, or to report a build that passes itself off as
CodeAssist, open an issue at https://github.com/tyron12233/CodeAssist/issues.
