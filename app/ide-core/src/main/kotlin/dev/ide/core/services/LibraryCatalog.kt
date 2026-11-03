package dev.ide.core.services

/**
 * Which library a class or package comes from, for the popular libraries a user is likely to type an import
 * of before adding the library: AndroidX, Material, Compose, Gson, OkHttp, Retrofit, Glide, Coil, Guava, the
 * kotlinx libraries, the test libraries.
 *
 * Bundled rather than looked up because the libraries people reach for most (everything `androidx.*`) live on
 * Google Maven, which has no class index to search; Maven Central's class search covers the rest
 * ([MissingLibraries] asks it for an import this table does not know). Working offline is the other reason.
 *
 * [Entry.version] is a known-good version, used when the newest one cannot be looked up (offline) and, for a
 * [Entry.pinned] entry, always: Compose tracks the IDE's own Compose, since @Preview runs the project against it.
 */
internal object LibraryCatalog {

    enum class Platform { ANY, ANDROID, JVM }

    class Entry(
        /** `group:name`. */
        val coordinate: String,
        val version: String,
        /** Packages whose every class this library provides (the most specific match wins). */
        val packages: List<String> = emptyList(),
        /**
         * Fully-qualified classes (or top-level functions) that pin this library over a package match, for a
         * package several artifacts share (`androidx.lifecycle`). Their simple names also answer an unknown
         * class name typed with no import ([forSimpleName]), so list only names that mean this one library.
         */
        val classes: List<String> = emptyList(),
        val platform: Platform = Platform.ANY,
        /** Use [version] as is: never look for a newer one. */
        val pinned: Boolean = false,
        /** Only versions ending in this count (Guava publishes `-android` and `-jre` flavors side by side). */
        val versionSuffix: String? = null,
        /** A test library: added to the test configuration. */
        val test: Boolean = false,
        /** The name shown in the fix, e.g. "Gson". */
        val label: String = coordinate.substringAfter(':'),
    ) {
        val group: String get() = coordinate.substringBefore(':')
        val name: String get() = coordinate.substringAfter(':')
        fun appliesTo(android: Boolean): Boolean = when (platform) {
            Platform.ANY -> true
            Platform.ANDROID -> android
            Platform.JVM -> !android
        }
        override fun toString() = "$coordinate:$version"
    }

    // Versions below are the offline fallback (and the fixed choice for pinned entries). Compose follows the
    // IDE's gradle/libs.versions.toml (androidxCompose, androidxComposeMaterial3, activityCompose).
    private const val COMPOSE = "1.11.2"
    private const val COMPOSE_M3 = "1.4.0"
    private const val LIFECYCLE = "2.8.7"
    private const val NAVIGATION = "2.8.5"
    private const val ROOM = "2.8.4"
    private const val CAMERA = "1.4.1"
    private const val MEDIA3 = "1.5.1"
    private const val RETROFIT = "2.11.0"
    private const val OKHTTP = "4.12.0"
    private const val COROUTINES = "1.11.0"

    val entries: List<Entry> = listOf(
        // ---- AndroidX: views and app components ----
        Entry("androidx.appcompat:appcompat", "1.7.0", listOf("androidx.appcompat"),
            classes("androidx.appcompat.app", "AppCompatActivity", "AlertDialog", "AppCompatDelegate"), Platform.ANDROID, label = "AppCompat"),
        Entry("androidx.core:core-ktx", "1.13.1", listOf("androidx.core"),
            classes("androidx.core.content", "ContextCompat", "FileProvider") +
                classes("androidx.core.app", "ActivityCompat", "NotificationCompat", "NotificationManagerCompat") +
                classes("androidx.core.view", "WindowCompat", "ViewCompat", "WindowInsetsCompat"),
            Platform.ANDROID, label = "AndroidX Core"),
        Entry("androidx.core:core-splashscreen", "1.0.1", listOf("androidx.core.splashscreen"), platform = Platform.ANDROID, label = "SplashScreen"),
        Entry("androidx.activity:activity-ktx", "1.9.3", listOf("androidx.activity"), platform = Platform.ANDROID, label = "Activity"),
        Entry("androidx.fragment:fragment-ktx", "1.8.5", listOf("androidx.fragment"),
            classes("androidx.fragment.app", "FragmentActivity", "DialogFragment", "FragmentManager"), Platform.ANDROID, label = "Fragment"),
        Entry("androidx.recyclerview:recyclerview", "1.3.2", listOf("androidx.recyclerview"),
            classes("androidx.recyclerview.widget", "RecyclerView", "LinearLayoutManager", "GridLayoutManager", "ListAdapter", "DiffUtil"),
            Platform.ANDROID, label = "RecyclerView"),
        Entry("androidx.constraintlayout:constraintlayout", "2.2.0", listOf("androidx.constraintlayout"),
            classes("androidx.constraintlayout.widget", "ConstraintLayout", "ConstraintSet"), Platform.ANDROID, label = "ConstraintLayout"),
        Entry("androidx.constraintlayout:constraintlayout-compose", "1.1.0", listOf("androidx.constraintlayout.compose"),
            platform = Platform.ANDROID, label = "ConstraintLayout for Compose"),
        Entry("androidx.cardview:cardview", "1.0.0", listOf("androidx.cardview"),
            classes("androidx.cardview.widget", "CardView"), Platform.ANDROID, label = "CardView"),
        Entry("androidx.viewpager2:viewpager2", "1.1.0", listOf("androidx.viewpager2"),
            classes("androidx.viewpager2.widget", "ViewPager2"), Platform.ANDROID, label = "ViewPager2"),
        Entry("androidx.viewpager:viewpager", "1.0.0", listOf("androidx.viewpager"), platform = Platform.ANDROID, label = "ViewPager"),
        Entry("androidx.swiperefreshlayout:swiperefreshlayout", "1.1.0", listOf("androidx.swiperefreshlayout"),
            classes("androidx.swiperefreshlayout.widget", "SwipeRefreshLayout"), Platform.ANDROID, label = "SwipeRefreshLayout"),
        Entry("androidx.coordinatorlayout:coordinatorlayout", "1.2.0", listOf("androidx.coordinatorlayout"),
            classes("androidx.coordinatorlayout.widget", "CoordinatorLayout"), Platform.ANDROID, label = "CoordinatorLayout"),
        Entry("androidx.drawerlayout:drawerlayout", "1.2.0", listOf("androidx.drawerlayout"),
            classes("androidx.drawerlayout.widget", "DrawerLayout"), Platform.ANDROID, label = "DrawerLayout"),
        Entry("androidx.preference:preference-ktx", "1.2.1", listOf("androidx.preference"),
            classes("androidx.preference", "PreferenceFragmentCompat", "PreferenceManager"), Platform.ANDROID, label = "Preference"),
        Entry("androidx.webkit:webkit", "1.12.1", listOf("androidx.webkit"), platform = Platform.ANDROID, label = "WebKit"),
        Entry("androidx.browser:browser", "1.8.0", listOf("androidx.browser"),
            classes("androidx.browser.customtabs", "CustomTabsIntent"), Platform.ANDROID, label = "Browser"),
        Entry("androidx.annotation:annotation", "1.9.1", listOf("androidx.annotation"), label = "AndroidX Annotations"),
        Entry("androidx.collection:collection-ktx", "1.4.5", listOf("androidx.collection"), label = "AndroidX Collection"),
        Entry("androidx.palette:palette-ktx", "1.0.0", listOf("androidx.palette"), platform = Platform.ANDROID, label = "Palette"),
        Entry("androidx.exifinterface:exifinterface", "1.3.7", listOf("androidx.exifinterface"), platform = Platform.ANDROID, label = "ExifInterface"),
        Entry("androidx.startup:startup-runtime", "1.2.0", listOf("androidx.startup"), platform = Platform.ANDROID, label = "App Startup"),
        Entry("androidx.window:window", "1.3.0", listOf("androidx.window"), platform = Platform.ANDROID, label = "WindowManager"),
        Entry("androidx.biometric:biometric", "1.1.0", listOf("androidx.biometric"),
            classes("androidx.biometric", "BiometricPrompt", "BiometricManager"), Platform.ANDROID, label = "Biometric"),
        Entry("androidx.security:security-crypto", "1.0.0", listOf("androidx.security.crypto"),
            classes("androidx.security.crypto", "EncryptedSharedPreferences", "MasterKey"), Platform.ANDROID, label = "Security Crypto"),
        Entry("androidx.work:work-runtime-ktx", "2.10.0", listOf("androidx.work"),
            classes("androidx.work", "WorkManager", "CoroutineWorker", "OneTimeWorkRequestBuilder", "PeriodicWorkRequestBuilder"),
            Platform.ANDROID, label = "WorkManager"),
        Entry("androidx.datastore:datastore-preferences", "1.1.1", listOf("androidx.datastore.preferences", "androidx.datastore"),
            platform = Platform.ANDROID, label = "DataStore"),
        Entry("androidx.paging:paging-runtime-ktx", "3.3.5", listOf("androidx.paging"),
            classes("androidx.paging", "PagingSource", "PagingData", "Pager", "PagingConfig"), Platform.ANDROID, label = "Paging"),
        Entry("androidx.paging:paging-compose", "3.3.5", listOf("androidx.paging.compose"), platform = Platform.ANDROID, label = "Paging for Compose"),

        // ---- Lifecycle: one package, several artifacts, told apart by class ----
        Entry("androidx.lifecycle:lifecycle-runtime-ktx", LIFECYCLE, listOf("androidx.lifecycle"),
            classes("androidx.lifecycle", "lifecycleScope", "repeatOnLifecycle", "LifecycleOwner", "Lifecycle", "LifecycleObserver", "DefaultLifecycleObserver"),
            Platform.ANDROID, label = "Lifecycle"),
        Entry("androidx.lifecycle:lifecycle-viewmodel-ktx", LIFECYCLE,
            classes = classes("androidx.lifecycle", "ViewModel", "ViewModelProvider", "AndroidViewModel", "viewModelScope", "SavedStateHandle", "ViewModelStoreOwner"),
            platform = Platform.ANDROID, label = "ViewModel"),
        Entry("androidx.lifecycle:lifecycle-livedata-ktx", LIFECYCLE,
            classes = classes("androidx.lifecycle", "LiveData", "MutableLiveData", "Observer", "MediatorLiveData", "liveData", "asLiveData", "Transformations"),
            platform = Platform.ANDROID, label = "LiveData"),
        Entry("androidx.lifecycle:lifecycle-process", LIFECYCLE, classes = classes("androidx.lifecycle", "ProcessLifecycleOwner"),
            platform = Platform.ANDROID, label = "Lifecycle Process"),
        Entry("androidx.lifecycle:lifecycle-viewmodel-compose", LIFECYCLE, listOf("androidx.lifecycle.viewmodel.compose"),
            platform = Platform.ANDROID, label = "ViewModel for Compose"),
        Entry("androidx.lifecycle:lifecycle-runtime-compose", LIFECYCLE, listOf("androidx.lifecycle.compose"),
            platform = Platform.ANDROID, label = "Lifecycle for Compose"),

        // ---- Navigation, Room, CameraX, Media3 ----
        Entry("androidx.navigation:navigation-runtime-ktx", NAVIGATION, listOf("androidx.navigation"), platform = Platform.ANDROID, label = "Navigation"),
        Entry("androidx.navigation:navigation-fragment-ktx", NAVIGATION, listOf("androidx.navigation.fragment"),
            classes("androidx.navigation.fragment", "NavHostFragment"), Platform.ANDROID, label = "Navigation Fragment"),
        Entry("androidx.navigation:navigation-ui-ktx", NAVIGATION, listOf("androidx.navigation.ui"), platform = Platform.ANDROID, label = "Navigation UI"),
        Entry("androidx.navigation:navigation-compose", NAVIGATION, listOf("androidx.navigation.compose"),
            classes("androidx.navigation.compose", "NavHost", "rememberNavController", "composable"), Platform.ANDROID, label = "Navigation for Compose"),
        Entry("androidx.room:room-runtime", ROOM, listOf("androidx.room"),
            classes("androidx.room", "Room", "RoomDatabase"), Platform.ANDROID, label = "Room"),
        Entry("androidx.camera:camera-core", CAMERA, listOf("androidx.camera.core"), platform = Platform.ANDROID, label = "CameraX"),
        Entry("androidx.camera:camera-camera2", CAMERA, listOf("androidx.camera.camera2"), platform = Platform.ANDROID, label = "CameraX Camera2"),
        Entry("androidx.camera:camera-lifecycle", CAMERA, listOf("androidx.camera.lifecycle"),
            classes("androidx.camera.lifecycle", "ProcessCameraProvider"), Platform.ANDROID, label = "CameraX Lifecycle"),
        Entry("androidx.camera:camera-view", CAMERA, listOf("androidx.camera.view"),
            classes("androidx.camera.view", "PreviewView"), Platform.ANDROID, label = "CameraX View"),
        Entry("androidx.media3:media3-exoplayer", MEDIA3, listOf("androidx.media3.exoplayer"),
            classes("androidx.media3.exoplayer", "ExoPlayer"), Platform.ANDROID, label = "Media3 ExoPlayer"),
        Entry("androidx.media3:media3-ui", MEDIA3, listOf("androidx.media3.ui"),
            classes("androidx.media3.ui", "PlayerView"), Platform.ANDROID, label = "Media3 UI"),
        Entry("androidx.media3:media3-common", MEDIA3, listOf("androidx.media3.common"), platform = Platform.ANDROID, label = "Media3"),

        // ---- Compose (pinned to the IDE's Compose) ----
        Entry("androidx.compose.runtime:runtime", COMPOSE, listOf("androidx.compose.runtime"), platform = Platform.ANDROID, pinned = true, label = "Compose Runtime"),
        Entry("androidx.compose.runtime:runtime-livedata", COMPOSE, listOf("androidx.compose.runtime.livedata"), platform = Platform.ANDROID, pinned = true, label = "Compose LiveData"),
        Entry("androidx.compose.ui:ui", COMPOSE, listOf("androidx.compose.ui"), platform = Platform.ANDROID, pinned = true, label = "Compose UI"),
        Entry("androidx.compose.ui:ui-tooling-preview", COMPOSE, listOf("androidx.compose.ui.tooling.preview"), platform = Platform.ANDROID, pinned = true, label = "Compose Preview"),
        Entry("androidx.compose.foundation:foundation", COMPOSE, listOf("androidx.compose.foundation"), platform = Platform.ANDROID, pinned = true, label = "Compose Foundation"),
        Entry("androidx.compose.animation:animation", COMPOSE, listOf("androidx.compose.animation"), platform = Platform.ANDROID, pinned = true, label = "Compose Animation"),
        Entry("androidx.compose.material:material", COMPOSE, listOf("androidx.compose.material"), platform = Platform.ANDROID, pinned = true, label = "Compose Material"),
        Entry("androidx.compose.material:material-icons-extended", "1.7.8", listOf("androidx.compose.material.icons"), platform = Platform.ANDROID, pinned = true, label = "Material Icons"),
        Entry("androidx.compose.material3:material3", COMPOSE_M3, listOf("androidx.compose.material3"), platform = Platform.ANDROID, pinned = true, label = "Material 3 for Compose"),
        Entry("androidx.activity:activity-compose", "1.13.0", listOf("androidx.activity.compose"),
            classes("androidx.activity.compose", "setContent", "rememberLauncherForActivityResult", "BackHandler"), Platform.ANDROID, pinned = true, label = "Activity Compose"),

        // ---- Google ----
        Entry("com.google.android.material:material", "1.12.0", listOf("com.google.android.material"),
            classes("com.google.android.material.button", "MaterialButton") +
                classes("com.google.android.material.floatingactionbutton", "FloatingActionButton", "ExtendedFloatingActionButton") +
                classes("com.google.android.material.snackbar", "Snackbar") +
                classes("com.google.android.material.bottomnavigation", "BottomNavigationView") +
                classes("com.google.android.material.tabs", "TabLayout", "TabLayoutMediator") +
                classes("com.google.android.material.textfield", "TextInputLayout", "TextInputEditText") +
                classes("com.google.android.material.appbar", "MaterialToolbar", "AppBarLayout", "CollapsingToolbarLayout") +
                classes("com.google.android.material.card", "MaterialCardView") +
                classes("com.google.android.material.dialog", "MaterialAlertDialogBuilder") +
                classes("com.google.android.material.bottomsheet", "BottomSheetDialog", "BottomSheetBehavior", "BottomSheetDialogFragment") +
                classes("com.google.android.material.navigation", "NavigationView") +
                classes("com.google.android.material.chip", "Chip", "ChipGroup"),
            Platform.ANDROID, label = "Material Components"),
        Entry("com.google.code.gson:gson", "2.11.0", listOf("com.google.gson"),
            classes("com.google.gson", "Gson", "GsonBuilder", "JsonParser", "TypeToken") + "com.google.gson.annotations.SerializedName", label = "Gson"),
        Entry("com.google.guava:guava", "33.4.0-android", listOf("com.google.common"),
            classes("com.google.common.base", "Preconditions", "Joiner", "Splitter", "Stopwatch") +
                classes("com.google.common.collect", "ImmutableMap", "ImmutableSet", "Lists", "Maps", "Sets", "Multimap", "ArrayListMultimap"),
            Platform.ANDROID, versionSuffix = "-android", label = "Guava"),
        Entry("com.google.guava:guava", "33.4.0-jre", listOf("com.google.common"),
            classes("com.google.common.base", "Preconditions", "Joiner", "Splitter", "Stopwatch") +
                classes("com.google.common.collect", "ImmutableMap", "ImmutableSet", "Lists", "Maps", "Sets", "Multimap", "ArrayListMultimap"),
            Platform.JVM, versionSuffix = "-jre", label = "Guava"),
        Entry("com.google.zxing:core", "3.5.3", listOf("com.google.zxing"), label = "ZXing"),
        Entry("com.google.truth:truth", "1.4.4", listOf("com.google.common.truth"), test = true, label = "Truth"),

        // ---- Square and networking ----
        Entry("com.squareup.okhttp3:okhttp", OKHTTP, listOf("okhttp3"),
            classes("okhttp3", "OkHttpClient", "Interceptor") + "okhttp3.MediaType.Companion.toMediaType", label = "OkHttp"),
        Entry("com.squareup.okhttp3:logging-interceptor", OKHTTP, listOf("okhttp3.logging"),
            classes("okhttp3.logging", "HttpLoggingInterceptor"), label = "OkHttp Logging"),
        Entry("com.squareup.okio:okio", "3.9.1", listOf("okio"), label = "Okio"),
        Entry("com.squareup.retrofit2:retrofit", RETROFIT, listOf("retrofit2"), classes("retrofit2", "Retrofit"), label = "Retrofit"),
        Entry("com.squareup.retrofit2:converter-gson", RETROFIT, listOf("retrofit2.converter.gson"),
            classes("retrofit2.converter.gson", "GsonConverterFactory"), label = "Retrofit Gson converter"),
        Entry("com.squareup.retrofit2:converter-moshi", RETROFIT, listOf("retrofit2.converter.moshi"),
            classes("retrofit2.converter.moshi", "MoshiConverterFactory"), label = "Retrofit Moshi converter"),
        Entry("com.squareup.retrofit2:converter-scalars", RETROFIT, listOf("retrofit2.converter.scalars"),
            classes("retrofit2.converter.scalars", "ScalarsConverterFactory"), label = "Retrofit Scalars converter"),
        Entry("com.squareup.moshi:moshi", "1.15.1", listOf("com.squareup.moshi"), classes("com.squareup.moshi", "Moshi", "JsonClass"), label = "Moshi"),
        Entry("com.squareup.moshi:moshi-kotlin", "1.15.1", listOf("com.squareup.moshi.kotlin"),
            classes("com.squareup.moshi.kotlin.reflect", "KotlinJsonAdapterFactory"), label = "Moshi Kotlin"),
        Entry("com.squareup.picasso:picasso", "2.8", listOf("com.squareup.picasso"), classes("com.squareup.picasso", "Picasso"), Platform.ANDROID, label = "Picasso"),
        Entry("com.github.bumptech.glide:glide", "4.16.0", listOf("com.bumptech.glide"), classes("com.bumptech.glide", "Glide"), Platform.ANDROID, label = "Glide"),
        Entry("io.coil-kt:coil", "2.7.0", listOf("coil"), platform = Platform.ANDROID, label = "Coil"),
        Entry("io.coil-kt:coil-compose", "2.7.0", listOf("coil.compose"), classes("coil.compose", "AsyncImage", "rememberAsyncImagePainter"), Platform.ANDROID, label = "Coil for Compose"),
        Entry("io.coil-kt.coil3:coil", "3.2.0", listOf("coil3"), label = "Coil 3"),
        Entry("io.coil-kt.coil3:coil-compose", "3.2.0", listOf("coil3.compose"), label = "Coil 3 for Compose"),
        Entry("io.coil-kt.coil3:coil-network-okhttp", "3.2.0", listOf("coil3.network.okhttp"), label = "Coil 3 OkHttp"),
        Entry("com.airbnb.android:lottie", "6.6.2", listOf("com.airbnb.lottie"),
            classes("com.airbnb.lottie", "LottieAnimationView"), Platform.ANDROID, label = "Lottie"),
        Entry("com.airbnb.android:lottie-compose", "6.6.2", listOf("com.airbnb.lottie.compose"), platform = Platform.ANDROID, label = "Lottie for Compose"),
        Entry("com.jakewharton.timber:timber", "5.0.1", listOf("timber.log"), classes("timber.log", "Timber"), Platform.ANDROID, label = "Timber"),
        Entry("com.journeyapps:zxing-android-embedded", "4.3.0", listOf("com.journeyapps.barcodescanner"),
            platform = Platform.ANDROID, label = "ZXing Android Embedded"),
        Entry("io.ktor:ktor-client-core", "3.1.3", listOf("io.ktor.client"), classes("io.ktor.client", "HttpClient"), label = "Ktor Client"),
        Entry("org.java-websocket:Java-WebSocket", "1.5.7", listOf("org.java_websocket"), label = "Java-WebSocket"),
        Entry("org.jsoup:jsoup", "1.18.3", listOf("org.jsoup"), classes("org.jsoup", "Jsoup"), label = "jsoup"),
        Entry("io.reactivex.rxjava3:rxjava", "3.1.10", listOf("io.reactivex.rxjava3"),
            classes("io.reactivex.rxjava3.core", "Observable", "Single", "Completable", "Flowable"), label = "RxJava"),
        Entry("io.reactivex.rxjava3:rxandroid", "3.0.2", listOf("io.reactivex.rxjava3.android"), platform = Platform.ANDROID, label = "RxAndroid"),
        Entry("org.greenrobot:eventbus", "3.3.1", listOf("org.greenrobot.eventbus"), classes("org.greenrobot.eventbus", "EventBus", "Subscribe"), label = "EventBus"),

        // ---- Kotlin libraries ----
        Entry("org.jetbrains.kotlinx:kotlinx-coroutines-android", COROUTINES, listOf("kotlinx.coroutines"),
            COROUTINE_CLASSES, Platform.ANDROID, label = "Kotlin Coroutines"),
        Entry("org.jetbrains.kotlinx:kotlinx-coroutines-core", COROUTINES, listOf("kotlinx.coroutines"),
            COROUTINE_CLASSES, Platform.JVM, label = "Kotlin Coroutines"),
        Entry("org.jetbrains.kotlinx:kotlinx-coroutines-test", COROUTINES, listOf("kotlinx.coroutines.test"), test = true, label = "Coroutines Test"),
        Entry("org.jetbrains.kotlinx:kotlinx-serialization-json", "1.8.1", listOf("kotlinx.serialization"),
            classes("kotlinx.serialization", "Serializable", "SerialName") + classes("kotlinx.serialization.json", "Json", "JsonObject", "JsonElement"),
            label = "Kotlin Serialization JSON"),
        Entry("org.jetbrains.kotlinx:kotlinx-datetime", "0.6.2", listOf("kotlinx.datetime"), label = "kotlinx-datetime"),
        Entry("org.jetbrains.kotlinx:kotlinx-collections-immutable", "0.3.8", listOf("kotlinx.collections.immutable"),
            classes("kotlinx.collections.immutable", "persistentListOf", "persistentMapOf", "toImmutableList"), label = "Immutable Collections"),
        Entry("org.jetbrains:annotations", "26.0.1", listOf("org.jetbrains.annotations"), label = "JetBrains Annotations"),

        // ---- Java libraries ----
        Entry("org.apache.commons:commons-lang3", "3.17.0", listOf("org.apache.commons.lang3"),
            classes("org.apache.commons.lang3", "StringUtils", "ArrayUtils", "RandomStringUtils"), label = "Commons Lang"),
        Entry("commons-io:commons-io", "2.18.0", listOf("org.apache.commons.io"), classes("org.apache.commons.io", "FileUtils", "IOUtils", "FilenameUtils"), label = "Commons IO"),
        Entry("org.apache.commons:commons-text", "1.13.0", listOf("org.apache.commons.text"), classes("org.apache.commons.text", "StringEscapeUtils"), label = "Commons Text"),
        Entry("org.apache.commons:commons-collections4", "4.4", listOf("org.apache.commons.collections4"), label = "Commons Collections"),
        Entry("commons-codec:commons-codec", "1.17.1", listOf("org.apache.commons.codec"), label = "Commons Codec"),
        Entry("com.fasterxml.jackson.core:jackson-databind", "2.18.2", listOf("com.fasterxml.jackson.databind"),
            classes("com.fasterxml.jackson.databind", "ObjectMapper", "JsonNode"), label = "Jackson Databind"),
        Entry("com.fasterxml.jackson.core:jackson-annotations", "2.18.2", listOf("com.fasterxml.jackson.annotation"), label = "Jackson Annotations"),
        Entry("com.fasterxml.jackson.core:jackson-core", "2.18.2", listOf("com.fasterxml.jackson.core"), label = "Jackson Core"),
        Entry("com.fasterxml.jackson.module:jackson-module-kotlin", "2.18.2", listOf("com.fasterxml.jackson.module.kotlin"), label = "Jackson Kotlin"),
        Entry("org.json:json", "20240303", listOf("org.json"), platform = Platform.JVM, label = "JSON-java"),
        Entry("org.slf4j:slf4j-api", "2.0.16", listOf("org.slf4j"), classes("org.slf4j", "LoggerFactory"), label = "SLF4J"),
        Entry("javax.inject:javax.inject", "1", listOf("javax.inject"), pinned = true, label = "javax.inject"),

        // ---- Tests ----
        Entry("junit:junit", "4.13.2", listOf("org.junit", "junit.framework"), classes("org.junit", "Assert", "Before", "After"), test = true, label = "JUnit 4"),
        Entry("org.junit.jupiter:junit-jupiter-api", "5.11.4", listOf("org.junit.jupiter.api"),
            classes("org.junit.jupiter.api", "Assertions", "BeforeEach", "AfterEach", "DisplayName"), test = true, label = "JUnit 5"),
        Entry("org.mockito:mockito-core", "5.14.2", listOf("org.mockito"), classes("org.mockito", "Mockito"), test = true, label = "Mockito"),
        Entry("org.mockito.kotlin:mockito-kotlin", "5.4.0", listOf("org.mockito.kotlin"), test = true, label = "Mockito-Kotlin"),
        Entry("io.mockk:mockk", "1.13.13", listOf("io.mockk"), test = true, label = "MockK"),
        Entry("androidx.test.ext:junit", "1.2.1", listOf("androidx.test.ext.junit"),
            classes("androidx.test.ext.junit.runners", "AndroidJUnit4"), Platform.ANDROID, test = true, label = "AndroidX Test JUnit"),
        Entry("androidx.test.espresso:espresso-core", "3.6.1", listOf("androidx.test.espresso"),
            classes("androidx.test.espresso", "Espresso"), Platform.ANDROID, test = true, label = "Espresso"),
    )

    /**
     * The library that provides [fqn] (a class, a nested class, or a top-level function or property), for a
     * module that is or is not [android]. An exact [Entry.classes] match wins, then the longest package.
     */
    fun forFqn(fqn: String, android: Boolean): Entry? {
        val clean = fqn.removeSuffix(".*")
        val candidates = entries.filter { it.appliesTo(android) }
        candidates.firstOrNull { e -> e.classes.any { it == clean || clean.startsWith("$it.") } }?.let { return it }
        var best: Entry? = null
        var bestLength = -1
        for (e in candidates) for (p in e.packages) {
            if ((clean == p || clean.startsWith("$p.")) && p.length > bestLength) {
                best = e
                bestLength = p.length
            }
        }
        return best
    }

    /** The library behind a class typed by its simple [name] with no import, and the class it means. */
    fun forSimpleName(name: String, android: Boolean): Pair<Entry, String>? {
        if (name.isEmpty() || !name[0].isUpperCase()) return null
        for (e in entries) {
            if (!e.appliesTo(android)) continue
            e.classes.firstOrNull { it.substringAfterLast('.') == name }?.let { return e to it }
        }
        return null
    }

    private fun classes(pkg: String, vararg names: String): List<String> = names.map { "$pkg.$it" }

    private val COROUTINE_CLASSES: List<String>
        get() = classes("kotlinx.coroutines", "CoroutineScope", "Dispatchers", "Job", "SupervisorJob", "Deferred", "MainScope") +
            classes("kotlinx.coroutines.flow", "Flow", "StateFlow", "MutableStateFlow", "SharedFlow", "MutableSharedFlow")
}
