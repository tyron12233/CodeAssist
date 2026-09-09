package dev.ide.fakecompose

/**
 * Mirrors Compose's `Brush`/`SolidColor`/`BorderStroke` overload idiom behind the report
 * `border = BorderStroke(2.dp, SolidColor(Color.Blue))`: `BorderStroke` has a public CONSTRUCTOR taking a
 * `Brush`, and a same-named top-level FACTORY FUNCTION taking a `Color`. A `FakeSolidColor` (which IS-A
 * `FakeBrush` through `FakeShaderBrush`) must bind to the CONSTRUCTOR — it must NOT be flagged against the
 * factory function's `FakeColor` parameter. Compiled into the test classpath so the binary (`@Metadata`)
 * overload path is exercised without the Compose toolchain.
 */
sealed class FakeBrush

abstract class FakeShaderBrush : FakeBrush()

class FakeColor

class FakeSolidColor(val value: FakeColor) : FakeShaderBrush()

class FakeStrokeWidth(val px: Int)

class FakeBorderStroke(val width: FakeStrokeWidth, val brush: FakeBrush)

fun FakeBorderStroke(width: FakeStrokeWidth, color: FakeColor): FakeBorderStroke =
    FakeBorderStroke(width, FakeSolidColor(color))
