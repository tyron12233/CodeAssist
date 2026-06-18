plugins {
    alias(libs.plugins.kotlin.jvm)
}

// deps-impl — the engine behind deps-api's DependencyResolver. Resolves `group:name:version`
// coordinates against Maven-layout repositories: fetches + parses .pom metadata (parent/properties/
// dependencyManagement merge), walks transitives with Maven scope-narrowing + exclusions, resolves
// version conflicts (newest-wins / fail / pinned), detects cycles, extracts `classes.jar` from .aar,
// and caches everything on disk under `.platform/caches/resolved-deps` (which doubles as the offline
// store). All network/file I/O is behind the injectable [ArtifactFetcher] port so the engine runs
// fully offline in tests against fixture repositories.
dependencies {
    implementation(project(":deps-api"))
    implementation(project(":project-model-api"))
    implementation(project(":platform-core"))
    implementation(project(":vfs-api"))
    implementation(libs.kotlinx.coroutines.core) // bounded-parallel POM resolution + artifact downloads

    testImplementation(project(":project-model-impl")) // LocalFileSystem for the VirtualFile factory in tests
    testImplementation(libs.kotlinx.coroutines.test)
}
