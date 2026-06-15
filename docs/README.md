# Documentation

Public documentation for the on-device Android/Java IDE framework. Start with the architecture overview
and follow the links into each subsystem.

- [architecture.md](architecture.md) — the project model, the two-level graph, the build-system
  abstraction, the concurrency model, and how extension points fit together.
- [modules.md](modules.md) — the module map: every module, its package(s), and its responsibility,
  with the acyclic dependency direction.
- [extension-points.md](extension-points.md) — the extension-point registry and every published
  extension point (module types, build systems, language backends, indexing, analysis, icons,
  templates, block mappings), plus the language-backend SPI.
- [language-support.md](language-support.md) — the backend-neutral DOM, code completion, the Java/XML/
  Kotlin backends, indexing, analysis/diagnostics/quick-fixes, and block editing.
- [kotlin-completion.md](kotlin-completion.md) — how on-device Kotlin completion works: parse-only PSI,
  the framework's own symbols/inference, and classpath metadata decoding.
- [block-editing.md](block-editing.md) — how the projectional (block) editor projects the shared DOM and
  round-trips block edits back to byte-for-byte source.
- [build-system.md](build-system.md) — the `BuildSystem` SPI, the incremental task engine, the native
  Java and Android pipelines, and the Gradle compatibility layer.
