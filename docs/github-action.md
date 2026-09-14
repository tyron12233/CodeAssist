# Building CodeAssist projects in CI

CodeAssist's build system is not Gradle. A project written on a phone has no `build.gradle`, no AGP and no
wrapper; it has a `.platform/workspace.json`, a `module.toml` per module, and an engine that goes
resolve → aapt2 → javac/K2 → D8 → apksigner. That engine is plain JVM code, so it runs on a CI runner
exactly as it runs on a device.

Two pieces make that usable:

- **`codeassist`**, a headless launcher (`:build-cli`) that opens a project, builds one task, and reports
  what came out.
- **the `codeassist-build` action**, a composite action that provisions a JDK and the Android SDK, fetches
  the launcher, and runs it, with compiler errors annotated onto the diff.

## Using the action

```yaml
name: APK
on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: tyron12233/CodeAssist@v1
        id: build
        with:
          variant: debug
          artifact-name: app-debug
      - run: echo "Built ${{ steps.build.outputs.apk }} in ${{ steps.build.outputs.elapsed-ms }}ms"
```

The project must be a CodeAssist project, meaning its `.platform/` directory is committed. That directory
is the model (modules, source roots, facets, declared dependencies); it is meant to be version-controlled,
the way a `build.gradle` is. `.platform/caches/` is not, and the default `.gitignore` a new project gets
already excludes it.

### Inputs

| Input | Default | What it does |
|---|---|---|
| `project` | `.` | Project directory, relative to the workspace |
| `variant` | `debug` | Variant to assemble |
| `module` | first app module | Module to assemble |
| `task` | | An exact task id (`bundle:app:release`), instead of `variant`/`module` |
| `cli-version` | `latest` | A release tag to pin the launcher to |
| `java-version` | `25` | JDK to install |
| `android-api` | `36` | Platform to install (the `android.jar` compiled against) |
| `build-tools` | `36.0.0` | Where `aapt2`, `d8` and `apksigner` come from |
| `setup-java` / `setup-android` | `true` | Skip provisioning if the job already did it |
| `cache` | `true` | Cache the launcher and the resolved dependencies |
| `artifact-name` | | When set, upload the built artifact under this name |
| `args` | | Extra flags for `codeassist` |

### Outputs

`apk`, `aab`, `outputs` (one path per line), `status`, `task`, `module`, `elapsed-ms`, `errors`, `report`
(a JSON file with the full diagnostic list).

### What a failure looks like

Every diagnostic the build produces carries a file, a line and often a column, so the action emits them as
workflow annotations:

```
::error file=app/src/main/java/com/example/MainActivity.java,line=14,title=ecj::Type mismatch: cannot convert from String to int
```

which GitHub renders on the pull request's diff. The job summary gets the verdict, the timing, and the
first twenty errors. The step exits 1, so the job fails.

## Using the launcher directly

The same binary works on a desktop, which is the quickest way to reproduce a CI failure:

```
codeassist assemble --project . --variant debug   # build; exits 0, or 1 if the build failed
codeassist tasks                                  # what this project can build
codeassist create --template android-app --name MyApp --package com.example.myapp --project ./MyApp
codeassist templates                              # what `create` accepts
```

Useful flags: `--task <id>` for anything that is not an assemble, `--cache <dir>` to put the
resolved-dependency cache outside the project, `--report <file>` for a JSON report, `--build-timeout <min>`.

The Android SDK is located through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, the project's `local.properties`, or
the per-OS default. Building an APK needs its build-tools and a platform; nothing else has to be on `PATH`,
because the pure-Java tools are launched on the JVM the launcher itself runs on.

Build it from a checkout with `./gradlew :build-cli:installDist`; the launcher lands in
`app/build-cli/build/install/codeassist/bin/`.

## How it fits together

`:build-cli` is a third host beside `:ide-desktop` and `:ide-android`, and the thinnest: argument parsing, a
transcript, and the workflow commands. The work happens in `HeadlessEngine`
(`app/ide-core/.../headless/HeadlessBuild.kt`), which opens `IdeServices` in `buildOnly` mode — the same
headless configuration the on-device `:build` daemon drives (see [build-process-isolation.md]). So a CI
build and a phone build are the same build, and a regression in one shows up in the other.

`.github/workflows/cli.yml` builds the distribution on every push and proves it: it scaffolds the
dependency-free `android-app` template, assembles it to a signed APK, then breaks a source file and asserts
the failure exits 1 with a file annotation. On a published release it attaches `codeassist-cli.zip` to the
release, which is the asset the action downloads.

Two things worth knowing about the shape of this:

- The distribution is around 220 MB unpacked (it carries the K2 compiler, the IntelliJ platform the parsers
  use, ecj, and the Compose desktop jars `:ide-core` api-exposes). The action caches it per release tag, so
  only the first run on a runner pays for the download.
- Because the action lives in this repository, `uses: tyron12233/CodeAssist@v1` checks out the whole
  repository (~73 MB) to get one `action.yml`. Splitting it into its own repository would make that a few
  kilobytes, and is the obvious next step if the action gets used widely.
