package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.core.IdeServicesBackend
import dev.ide.preview.PreviewRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Interpret-mode render of a layout with a `com.google.android.material.bottomnavigation.BottomNavigationView`
 * that inflates a menu via `app:menu`. Exercises NavigationBarView's menu inflation (SupportMenuInflater,
 * MenuBuilder) and item-view creation, which read `android.view.View.EMPTY_STATE_SET` — a static array declared
 * on the real `View` super and accessed through an interpreted subclass. The VM used to read that inherited
 * static as null (it searched only the interpreted chain), so `obtainStyledAttributes` got a null array and the
 * inflate failed; the fix bridges an inherited-real static field read/write to the real super. Asserts the
 * layout renders with no owned-rendering fallback.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.RealViewBottomNavSpike
 *     adb logcat -d -s VmBottomNav ide.preview
 */
@RunWith(AndroidJUnit4::class)
class RealViewBottomNavSpike {

    private fun log(m: String) { Log.i("VmBottomNav", m); println(m) }

    private val menu = """
        <?xml version="1.0" encoding="utf-8"?>
        <menu xmlns:android="http://schemas.android.com/apk/res/android">
            <item
                android:id="@+id/nav_home"
                android:icon="@android:drawable/ic_menu_compass"
                android:title="Home" />
            <item
                android:id="@+id/nav_search"
                android:icon="@android:drawable/ic_menu_search"
                android:title="Search" />
            <item
                android:id="@+id/nav_profile"
                android:icon="@android:drawable/ic_menu_myplaces"
                android:title="Profile" />
        </menu>
    """.trimIndent()

    private val layout = """
        <?xml version="1.0" encoding="utf-8"?>
        <androidx.coordinatorlayout.widget.CoordinatorLayout
            xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="center"
                android:text="@string/hello_world" />

            <com.google.android.material.bottomnavigation.BottomNavigationView
                android:id="@+id/bottom_nav"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_gravity="bottom"
                app:menu="@menu/bottom_nav_menu" />

        </androidx.coordinatorlayout.widget.CoordinatorLayout>
    """.trimIndent()

    @Test
    fun rendersBottomNavigationView() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = AndroidIde.createProjectManager(ctx)
        val services = pm.create("android-material-you", emptyMap())
        val backend = IdeServicesBackend(initial = services)
        try {
            backend.deps.startPendingDependencyResolution()
            val deadline = System.currentTimeMillis() + 180_000
            Thread.sleep(2_000)
            while (backend.deps.depsState.value.resolving && System.currentTimeMillis() < deadline) Thread.sleep(1_000)
            log("deps settled; unresolved=${backend.deps.depsState.value.unresolved}")

            val root = services.store.rootPath.toFile()
            File(root, "app/src/main/res/menu").mkdirs()
            File(root, "app/src/main/res/menu/bottom_nav_menu.xml").writeText(menu)
            val file = File(root, "app/src/main/res/layout/activity_main.xml")
            file.writeText(layout)

            val r = services.layoutPreview(
                file.toPath(), layout,
                PreviewRequest(widthPx = 1080, heightPx = 2140, density = 2.75f, realViews = true),
            )
            log("rendered=${r?.renderedNativeImage != null} problems=${r?.problems?.map { it.message }}")
            assertTrue(
                "BottomNavigationView should render with no fallback: ${r?.problems?.map { it.message }}",
                r?.renderedNativeImage != null && r.problems.isEmpty(),
            )
        } finally {
            runCatching { services.close() }
        }
    }
}
