plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// android-layout-api: what the IDE offers a plugin that works on Android layout XML. A layout editor (the
// built-in one is a plugin, `:layout-editor`) renders through [LayoutRenderer], reads and edits the layout
// through [AndroidLayoutModel], and finds the views it can place through [AndroidViewCatalog]. The host
// implements all of them over its own machinery (the real-view renderer, the XML parser, the SDK attribute
// metadata and the project's indexes); nothing here names an engine type.
//
// IN-TREE ONLY for now: it is not in plugin-bom and not pinned by :spi-compat. It is published once the
// built-in layout editor has proved the shape (see docs/plugin-system.md).
dependencies {
    // ServiceKey and the coroutines StateFlow appear in the public surface.
    api(project(":model-api"))
}
