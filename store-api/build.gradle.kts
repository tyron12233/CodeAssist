plugins {
    alias(libs.plugins.kotlin.jvm)
}

// store-api — the contract for the remote Projects Store.
//
// A dependency-free SPI: the catalog model, and three ports the engine talks to —
// StoreCatalogSource (fetch the catalog / search / count an install), StoreAccount (sign in, who am I),
// and StoreSubmissions (package and submit a project for review). The HTTP, the zip packing and the
// offline cache all live in store-impl, so the transport is swappable and the engine never sees
// Supabase. See supabase/README.md for the backend these map onto.
dependencies {
}
