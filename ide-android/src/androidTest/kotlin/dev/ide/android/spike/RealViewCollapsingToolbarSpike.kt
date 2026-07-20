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
 * Comprehensive interpret-mode render: a Material You profile layout (CoordinatorLayout + AppBarLayout +
 * CollapsingToolbarLayout + parallax ImageView + MaterialToolbar + NestedScrollView with a string
 * `app:layout_behavior` + two MaterialCardViews + ConstraintLayout + MaterialButton + TextInputLayout +
 * MaterialSwitch + Slider + ChipGroup + anchored FAB). Exercises the VM fixes this layout drove: serialized
 * execution (async font callback re-entering on the main thread), inherited `static` methods bridged to the
 * real super (`ViewGroup.getChildMeasureSpec`), and reflective instantiation of an interpreted class (the
 * scroll behavior named by `@string/appbar_scrolling_view_behavior`, created via
 * `loadClass`/`asSubclass`/`getConstructor`/`newInstance`). Asserts it renders with no owned-rendering fallback.
 */
@RunWith(AndroidJUnit4::class)
class RealViewCollapsingToolbarSpike {

    private fun log(m: String) { Log.i("VmCTL", m); println(m) }

    // The user's full profile layout: CollapsingToolbar + parallax image + NestedScrollView (with a
    // string `layout_behavior`, reflectively instantiated) + MaterialCardView + ConstraintLayout +
    // TextInputLayout + MaterialSwitch + Slider + ChipGroup + anchored FAB + BottomNav.
    private val layout = """
        <?xml version="1.0" encoding="utf-8"?>
        <androidx.coordinatorlayout.widget.CoordinatorLayout
            xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:fitsSystemWindows="true">

            <com.google.android.material.appbar.AppBarLayout
                android:id="@+id/appBarLayout"
                android:layout_width="match_parent"
                android:layout_height="250dp"
                android:fitsSystemWindows="true"
                app:liftOnScroll="true">

                <com.google.android.material.appbar.CollapsingToolbarLayout
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    app:contentScrim="?attr/colorPrimary"
                    app:expandedTitleTextColor="?attr/colorOnPrimary"
                    app:collapsedTitleTextColor="?attr/colorOnPrimary"
                    app:layout_scrollFlags="scroll|exitUntilCollapsed|snap"
                    app:toolbarId="@+id/toolbar">

                    <ImageView
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:scaleType="centerCrop"
                        app:layout_collapseMode="parallax"
                        app:layout_collapseParallaxMultiplier="0.5" />

                    <com.google.android.material.appbar.MaterialToolbar
                        android:id="@+id/toolbar"
                        android:layout_width="match_parent"
                        android:layout_height="?attr/actionBarSize"
                        app:layout_collapseMode="pin"
                        app:navigationIcon="@android:drawable/ic_menu_sort_by_size"
                        app:title="Material You Profile" />

                </com.google.android.material.appbar.CollapsingToolbarLayout>
            </com.google.android.material.appbar.AppBarLayout>

            <androidx.core.widget.NestedScrollView
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                app:layout_behavior="@string/appbar_scrolling_view_behavior">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp"
                    android:clipToPadding="false"
                    android:paddingBottom="80dp">

                    <com.google.android.material.card.MaterialCardView
                        style="@style/Widget.Material3.CardView.Elevated"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="16dp"
                        app:cardElevation="4dp">

                        <androidx.constraintlayout.widget.ConstraintLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:padding="16dp">

                            <ImageView
                                android:id="@+id/avatar"
                                android:layout_width="64dp"
                                android:layout_height="64dp"
                                android:background="@android:drawable/sym_def_app_icon"
                                app:layout_constraintStart_toStartOf="parent"
                                app:layout_constraintTop_toTopOf="parent" />

                            <TextView
                                android:id="@+id/tvName"
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_marginStart="16dp"
                                android:text="Alex Developer"
                                android:textAppearance="?attr/textAppearanceHeadlineSmall"
                                android:textColor="?attr/colorOnSurface"
                                app:layout_constraintEnd_toEndOf="parent"
                                app:layout_constraintStart_toEndOf="@id/avatar"
                                app:layout_constraintTop_toTopOf="@id/avatar" />

                            <com.google.android.material.button.MaterialButton
                                style="@style/Widget.Material3.Button.TonalButton"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginTop="16dp"
                                android:text="Follow"
                                app:layout_constraintStart_toStartOf="parent"
                                app:layout_constraintTop_toBottomOf="@id/avatar" />

                        </androidx.constraintlayout.widget.ConstraintLayout>
                    </com.google.android.material.card.MaterialCardView>

                    <com.google.android.material.card.MaterialCardView
                        style="@style/Widget.Material3.CardView.Outlined"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="16dp">

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:orientation="vertical"
                            android:padding="16dp">

                            <com.google.android.material.textfield.TextInputLayout
                                style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:hint="Status update"
                                app:endIconMode="clear_text">

                                <com.google.android.material.textfield.TextInputEditText
                                    android:layout_width="match_parent"
                                    android:layout_height="wrap_content"
                                    android:inputType="text" />
                            </com.google.android.material.textfield.TextInputLayout>

                            <com.google.android.material.materialswitch.MaterialSwitch
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:layout_marginTop="16dp"
                                android:text="Enable Notifications"
                                android:checked="true" />

                            <com.google.android.material.slider.Slider
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:stepSize="10.0"
                                android:valueFrom="0.0"
                                android:valueTo="100.0"
                                android:value="50.0" />

                        </LinearLayout>
                    </com.google.android.material.card.MaterialCardView>

                    <com.google.android.material.chip.ChipGroup
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        app:singleSelection="false">

                        <com.google.android.material.chip.Chip
                            style="@style/Widget.Material3.Chip.Filter"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="Kotlin"
                            android:checked="true" />

                        <com.google.android.material.chip.Chip
                            style="@style/Widget.Material3.Chip.Filter"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="Material Design 3"
                            android:checked="true" />
                    </com.google.android.material.chip.ChipGroup>

                </LinearLayout>
            </androidx.core.widget.NestedScrollView>

            <com.google.android.material.floatingactionbutton.FloatingActionButton
                android:id="@+id/fab"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="16dp"
                android:contentDescription="Edit Profile"
                app:layout_anchor="@id/appBarLayout"
                app:layout_anchorGravity="bottom|end"
                app:srcCompat="@android:drawable/ic_menu_edit" />

        </androidx.coordinatorlayout.widget.CoordinatorLayout>
    """.trimIndent()

    @Test
    fun rendersCollapsingToolbar() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = AndroidIde.createProjectManager(ctx)
        val services = pm.create("android-material-you", emptyMap())
        val backend = IdeServicesBackend(initial = services)
        try {
            backend.deps.startPendingDependencyResolution()
            val deadline = System.currentTimeMillis() + 180_000
            Thread.sleep(2_000)
            while (backend.deps.depsState.value.resolving && System.currentTimeMillis() < deadline) Thread.sleep(1_000)

            val file = File(services.store.rootPath.toFile(), "app/src/main/res/layout/activity_main.xml")
            file.writeText(layout)
            val r = services.layoutPreview(
                file.toPath(), layout,
                PreviewRequest(widthPx = 1080, heightPx = 2140, density = 2.75f, realViews = true),
            )
            log("rendered=${r?.renderedNativeImage != null} problems=${r?.problems?.map { it.message }}")
            assertTrue(
                "CollapsingToolbarLayout should render with no fallback: ${r?.problems?.map { it.message }}",
                r?.renderedNativeImage != null && r.problems.isEmpty(),
            )
        } finally {
            runCatching { services.close() }
        }
    }
}
