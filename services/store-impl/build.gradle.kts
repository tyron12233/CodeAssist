plugins {
    alias(libs.plugins.kotlin.jvm)
}

// store-impl — the engine behind store-api.
//
// SupabaseStoreSource: the catalog transport, POSTing to Supabase PostgREST over
// java.net.HttpURLConnection (the same stdlib-only path the analytics sink and the dependency resolver
// use — present on JVM and ART, no extra dependency). CachedCatalogSource wraps it with the on-disk
// cache that makes the store readable offline. ProjectPackager zips a project for submission, excluding
// build output and anything secret. Pure stdlib.
dependencies {
    implementation(project(":store-api"))
    // The logging facade, for transport failures that should reach the console rather than be swallowed.
    implementation(project(":platform-core"))
    testImplementation(project(":test-support"))
}
