package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.core.IdeServicesBackend
import dev.ide.model.impl.rootPath
import dev.ide.preview.PreviewRequest
import dev.ide.preview.PreviewViewNode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Interpret-mode ConstraintLayout geometry: renders one fixture per ConstraintLayout feature (sibling/parent
 * constraints, centering + bias, match-constraint, guidelines, chains, barriers, ratios, groups + gone
 * margins, wrap_content, Flow, circular) through the real-view path and checks each child's laid-out bounds
 * from the captured view tree against what ConstraintLayout computes on a real device. Rendering without a
 * fallback is not enough here: a solver that runs but places views wrong still "renders".
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.RealViewConstraintLayoutSpike
 *     adb logcat -d -s VmCL ide.preview
 */
@RunWith(AndroidJUnit4::class)
class RealViewConstraintLayoutSpike {

    private fun log(m: String) { Log.i("VmCL", m); println(m) }

    /** One expected box, relative to the ConstraintLayout `@id/root`, as a function of the root's size. */
    private class Expect(val id: String, val box: (w: Int, h: Int) -> IntArray)

    private class Fixture(val name: String, val body: String, val expects: List<Expect>, val rootHeight: String = "match_parent")

    private fun box(l: Number, t: Number, w: Number, h: Number) = intArrayOf(l.toInt(), t.toInt(), w.toInt(), h.toInt())

    // Density 2.75: 40dp=110, 80dp=220, 100dp=275, 120dp=330, 16dp=44, 8dp=22.
    private val fixtures = listOf(
        Fixture(
            "basic",
            """
            <View android:id="@+id/a" android:layout_width="100dp" android:layout_height="100dp" android:background="#f44"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/b" android:layout_width="100dp" android:layout_height="100dp" android:background="#4f4"
                android:layout_marginStart="16dp"
                app:layout_constraintStart_toEndOf="@id/a" app:layout_constraintTop_toBottomOf="@id/a" />
            <View android:id="@+id/c" android:layout_width="100dp" android:layout_height="100dp" android:background="#44f"
                app:layout_constraintEnd_toEndOf="parent" app:layout_constraintBottom_toBottomOf="parent" />
            <View android:id="@+id/d" android:layout_width="100dp" android:layout_height="100dp" android:background="#ff4"
                app:layout_constraintLeft_toLeftOf="parent" app:layout_constraintRight_toRightOf="parent"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/e" android:layout_width="100dp" android:layout_height="100dp" android:background="#4ff"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toBottomOf="@id/f" />
            <View android:id="@+id/f" android:layout_width="100dp" android:layout_height="100dp" android:background="#f4f"
                android:layout_marginTop="300dp"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent" />
            """,
            listOf(
                Expect("a") { _, _ -> box(0, 0, 275, 275) },
                Expect("b") { _, _ -> box(275 + 44, 275, 275, 275) },
                Expect("c") { w, h -> box(w - 275, h - 275, 275, 275) },
                Expect("d") { w, _ -> box((w - 275) / 2, 0, 275, 275) },
                Expect("f") { _, _ -> box(0, 825, 275, 275) },
                Expect("e") { _, _ -> box(0, 825 + 275, 275, 275) },
            ),
        ),
        Fixture(
            "center-bias-matchconstraint",
            """
            <View android:id="@+id/a" android:layout_width="100dp" android:layout_height="100dp" android:background="#f44"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent" app:layout_constraintBottom_toBottomOf="parent" />
            <View android:id="@+id/b" android:layout_width="100dp" android:layout_height="100dp" android:background="#4f4"
                app:layout_constraintHorizontal_bias="0.25"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/c" android:layout_width="0dp" android:layout_height="40dp" android:background="#44f"
                android:layout_marginStart="16dp" android:layout_marginEnd="16dp"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintBottom_toBottomOf="parent" />
            """,
            listOf(
                Expect("a") { w, h -> box((w - 275) / 2, (h - 275) / 2, 275, 275) },
                Expect("b") { w, _ -> box((w - 275) * 0.25f, 0, 275, 275) },
                Expect("c") { w, h -> box(44, h - 110, w - 88, 110) },
            ),
        ),
        Fixture(
            "guidelines",
            """
            <androidx.constraintlayout.widget.Guideline android:id="@+id/gv" android:layout_width="wrap_content"
                android:layout_height="wrap_content" android:orientation="vertical"
                app:layout_constraintGuide_percent="0.5" />
            <androidx.constraintlayout.widget.Guideline android:id="@+id/gh" android:layout_width="wrap_content"
                android:layout_height="wrap_content" android:orientation="horizontal"
                app:layout_constraintGuide_begin="100dp" />
            <View android:id="@+id/a" android:layout_width="40dp" android:layout_height="40dp" android:background="#f44"
                app:layout_constraintStart_toStartOf="@id/gv" app:layout_constraintTop_toTopOf="@id/gh" />
            <View android:id="@+id/b" android:layout_width="40dp" android:layout_height="40dp" android:background="#4f4"
                app:layout_constraintEnd_toEndOf="@id/gv" app:layout_constraintBottom_toTopOf="@id/gh" />
            """,
            listOf(
                Expect("a") { w, _ -> box(w / 2, 275, 110, 110) },
                Expect("b") { w, _ -> box(w / 2 - 110, 275 - 110, 110, 110) },
            ),
        ),
        Fixture(
            "chains",
            """
            <View android:id="@+id/a1" android:layout_width="40dp" android:layout_height="40dp" android:background="#f44"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toStartOf="@id/a2"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/a2" android:layout_width="40dp" android:layout_height="40dp" android:background="#4f4"
                app:layout_constraintStart_toEndOf="@id/a1" app:layout_constraintEnd_toStartOf="@id/a3"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/a3" android:layout_width="40dp" android:layout_height="40dp" android:background="#44f"
                app:layout_constraintStart_toEndOf="@id/a2" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent" />

            <View android:id="@+id/b1" android:layout_width="40dp" android:layout_height="40dp" android:background="#f44"
                android:layout_marginTop="100dp" app:layout_constraintHorizontal_chainStyle="packed"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toStartOf="@id/b2"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/b2" android:layout_width="40dp" android:layout_height="40dp" android:background="#4f4"
                android:layout_marginTop="100dp"
                app:layout_constraintStart_toEndOf="@id/b1" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent" />

            <View android:id="@+id/c1" android:layout_width="0dp" android:layout_height="40dp" android:background="#f44"
                android:layout_marginTop="200dp" app:layout_constraintHorizontal_weight="1"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toStartOf="@id/c2"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/c2" android:layout_width="0dp" android:layout_height="40dp" android:background="#4f4"
                android:layout_marginTop="200dp" app:layout_constraintHorizontal_weight="2"
                app:layout_constraintStart_toEndOf="@id/c1" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent" />

            <View android:id="@+id/d1" android:layout_width="40dp" android:layout_height="40dp" android:background="#f44"
                android:layout_marginTop="300dp" app:layout_constraintHorizontal_chainStyle="spread_inside"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toStartOf="@id/d2"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/d2" android:layout_width="40dp" android:layout_height="40dp" android:background="#4f4"
                android:layout_marginTop="300dp"
                app:layout_constraintStart_toEndOf="@id/d1" app:layout_constraintEnd_toStartOf="@id/d3"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/d3" android:layout_width="40dp" android:layout_height="40dp" android:background="#44f"
                android:layout_marginTop="300dp"
                app:layout_constraintStart_toEndOf="@id/d2" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent" />
            """,
            listOf(
                Expect("a1") { w, _ -> box((w - 330) / 4f, 0, 110, 110) },
                Expect("a2") { w, _ -> box((w - 330) / 4f * 2 + 110, 0, 110, 110) },
                Expect("a3") { w, _ -> box((w - 330) / 4f * 3 + 220, 0, 110, 110) },
                Expect("b1") { w, _ -> box((w - 220) / 2, 275, 110, 110) },
                Expect("b2") { w, _ -> box((w - 220) / 2 + 110, 275, 110, 110) },
                Expect("c1") { w, _ -> box(0, 550, w / 3f, 110) },
                Expect("c2") { w, _ -> box(w / 3f, 550, w * 2 / 3f, 110) },
                Expect("d1") { _, _ -> box(0, 825, 110, 110) },
                Expect("d2") { w, _ -> box((w - 110) / 2, 825, 110, 110) },
                Expect("d3") { w, _ -> box(w - 110, 825, 110, 110) },
            ),
        ),
        Fixture(
            "barrier",
            """
            <View android:id="@+id/a" android:layout_width="80dp" android:layout_height="40dp" android:background="#f44"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/b" android:layout_width="120dp" android:layout_height="40dp" android:background="#4f4"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toBottomOf="@id/a" />
            <androidx.constraintlayout.widget.Barrier android:id="@+id/barrier" android:layout_width="wrap_content"
                android:layout_height="wrap_content" app:barrierDirection="end"
                app:constraint_referenced_ids="a,b" />
            <View android:id="@+id/c" android:layout_width="40dp" android:layout_height="40dp" android:background="#44f"
                app:layout_constraintStart_toEndOf="@id/barrier" app:layout_constraintTop_toTopOf="parent" />
            """,
            listOf(
                Expect("a") { _, _ -> box(0, 0, 220, 110) },
                Expect("b") { _, _ -> box(0, 110, 330, 110) },
                Expect("c") { _, _ -> box(330, 0, 110, 110) },
            ),
        ),
        Fixture(
            "ratio",
            """
            <View android:id="@+id/a" android:layout_width="0dp" android:layout_height="0dp" android:background="#f44"
                app:layout_constraintDimensionRatio="16:9"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/b" android:layout_width="100dp" android:layout_height="0dp" android:background="#4f4"
                app:layout_constraintDimensionRatio="1:1"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toBottomOf="@id/a" />
            """,
            listOf(
                Expect("a") { w, _ -> box(0, 0, w, w * 9 / 16f) },
                Expect("b") { w, _ -> box(0, w * 9 / 16f, 275, 275) },
            ),
        ),
        Fixture(
            "group-gone",
            """
            <View android:id="@+id/a" android:layout_width="100dp" android:layout_height="100dp" android:background="#f44"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/b" android:layout_width="100dp" android:layout_height="100dp" android:background="#4f4"
                android:layout_marginStart="16dp" app:layout_goneMarginStart="8dp"
                app:layout_constraintStart_toEndOf="@id/a" app:layout_constraintTop_toTopOf="parent" />
            <androidx.constraintlayout.widget.Group android:id="@+id/group" android:layout_width="wrap_content"
                android:layout_height="wrap_content" android:visibility="gone"
                app:constraint_referenced_ids="a" />
            """,
            listOf(
                Expect("b") { _, _ -> box(22, 0, 275, 275) },
            ),
        ),
        Fixture(
            "wrap-content",
            """
            <View android:id="@+id/a" android:layout_width="100dp" android:layout_height="100dp" android:background="#f44"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/b" android:layout_width="40dp" android:layout_height="40dp" android:background="#4f4"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toBottomOf="@id/a" />
            """,
            listOf(
                Expect("root") { w, _ -> box(0, 0, w, 275 + 110) },
            ),
            rootHeight = "wrap_content",
        ),
        Fixture(
            "flow",
            """
            <androidx.constraintlayout.helper.widget.Flow android:id="@+id/flow" android:layout_width="0dp"
                android:layout_height="wrap_content" app:flow_wrapMode="aligned" app:flow_horizontalStyle="packed"
                app:flow_horizontalBias="0"
                app:constraint_referenced_ids="v1,v2,v3,v4,v5"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent" />
            <View android:id="@+id/v1" android:layout_width="100dp" android:layout_height="100dp" android:background="#f44" />
            <View android:id="@+id/v2" android:layout_width="100dp" android:layout_height="100dp" android:background="#4f4" />
            <View android:id="@+id/v3" android:layout_width="100dp" android:layout_height="100dp" android:background="#44f" />
            <View android:id="@+id/v4" android:layout_width="100dp" android:layout_height="100dp" android:background="#ff4" />
            <View android:id="@+id/v5" android:layout_width="100dp" android:layout_height="100dp" android:background="#4ff" />
            """,
            listOf(
                Expect("v1") { _, _ -> box(0, 0, 275, 275) },
                Expect("v2") { _, _ -> box(275, 0, 275, 275) },
                Expect("v3") { _, _ -> box(550, 0, 275, 275) },
                Expect("v4") { _, _ -> box(0, 275, 275, 275) },
            ),
        ),
        Fixture(
            "circle",
            """
            <View android:id="@+id/a" android:layout_width="40dp" android:layout_height="40dp" android:background="#f44"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent" app:layout_constraintBottom_toBottomOf="parent" />
            <View android:id="@+id/b" android:layout_width="40dp" android:layout_height="40dp" android:background="#4f4"
                app:layout_constraintCircle="@id/a" app:layout_constraintCircleRadius="100dp"
                app:layout_constraintCircleAngle="90" />
            """,
            listOf(
                Expect("a") { w, h -> box((w - 110) / 2, (h - 110) / 2, 110, 110) },
                Expect("b") { w, h -> box((w - 110) / 2 + 275, (h - 110) / 2, 110, 110) },
            ),
        ),
    )

    // Built by concatenation: interpolating the unindented body into a trimIndent() template would leave the
    // template's indent before `<?xml`, which aapt2 rejects.
    private fun layoutOf(f: Fixture) = """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="${f.rootHeight}">
""" + f.body.trimIndent() + "\n</androidx.constraintlayout.widget.ConstraintLayout>\n"

    private fun find(n: PreviewViewNode, id: String): PreviewViewNode? =
        if (n.id == id) n else n.children.firstNotNullOfOrNull { find(it, id) }

    private fun dump(n: PreviewViewNode, depth: Int = 0) {
        log("${"  ".repeat(depth)}${n.simpleName} id=${n.id} [${n.left},${n.top} ${n.width}x${n.height}]")
        n.children.forEach { dump(it, depth + 1) }
    }

    @Test
    fun constraintLayoutGeometry() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = AndroidIde.createProjectManager(ctx)
        val services = pm.create("android-material-you", emptyMap())
        val backend = IdeServicesBackend(initial = services)
        val failures = ArrayList<String>()
        try {
            backend.deps.startPendingDependencyResolution()
            val deadline = System.currentTimeMillis() + 180_000
            Thread.sleep(2_000)
            while (backend.deps.depsState.value.resolving && System.currentTimeMillis() < deadline) Thread.sleep(1_000)
            log("deps settled; unresolved=${backend.deps.depsState.value.unresolved}")

            val file = File(services.store.rootPath.toFile(), "app/src/main/res/layout/activity_main.xml")
            // Each fixture renders twice: first SAVED (written to disk, as after Ctrl+S), then as an UNSAVED
            // buffer of the next edit (disk unchanged), the two latency cases the preview has.
            for ((i, f) in fixtures.withIndex()) {
                val xml = layoutOf(f)
                if (i % 2 == 0) file.writeText(xml)
                log("--- ${f.name} (${if (i % 2 == 0) "saved" else "unsaved buffer"})")
                // The editor reports every buffer change before the preview asks for a render.
                services.updateDocument(file.toPath(), xml)
                val t0 = System.nanoTime()
                val r = services.layoutPreview(
                    file.toPath(), xml,
                    PreviewRequest(widthPx = 1080, heightPx = 2140, density = 2.75f, realViews = true),
                )
                val ms = (System.nanoTime() - t0) / 1_000_000
                val problems = r?.problems?.map { it.message }.orEmpty()
                val rendered = r?.renderedNativeImage != null || r?.renderedImage != null
                log(
                    "=== ${f.name}: ${ms}ms rendered=$rendered native=${r?.renderedNativeImage != null} " +
                        "png=${r?.renderedImage?.size} tree=${r?.viewTree != null} buildRequired=${r?.buildRequired} " +
                        "resourceErrors=${r?.resourceErrors?.map { it.message }} problems=$problems"
                )
                if (!rendered || problems.isNotEmpty()) {
                    failures += "${f.name}: render failed: $problems ${r?.resourceErrors?.map { it.message }}"
                    continue
                }
                val tree = r?.viewTree
                if (tree == null) { failures += "${f.name}: no view tree"; continue }
                dump(tree)
                val root = find(tree, "root")
                if (root == null) { failures += "${f.name}: no @id/root in tree"; continue }
                for (e in f.expects) {
                    val n = find(tree, e.id)
                    if (n == null) { failures += "${f.name}/${e.id}: missing"; continue }
                    // Root-relative; for the root itself, its own origin.
                    val actual = if (e.id == "root") intArrayOf(0, 0, n.width, n.height)
                    else intArrayOf(n.left - root.left, n.top - root.top, n.width, n.height)
                    val want = e.box(root.width, root.height)
                    val ok = actual.indices.all { abs(actual[it] - want[it]) <= 2 }
                    val line = "${f.name}/${e.id}: want ${want.toList()} got ${actual.toList()}"
                    log((if (ok) "  ok   " else "  FAIL ") + line)
                    if (!ok) failures += line
                }
            }
        } finally {
            runCatching { services.close() }
        }
        log("failures (${failures.size}):\n" + failures.joinToString("\n"))
        assertTrue("ConstraintLayout geometry mismatches:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
