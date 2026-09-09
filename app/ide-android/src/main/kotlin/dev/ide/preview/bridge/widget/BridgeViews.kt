package dev.ide.preview.bridge.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * The owned view base a user custom view is reparented onto (`extends android.view.View`). The
 * `(Context, AttributeSet)` ctor drops the attrs for the `View` base (the user reads them via the
 * instrumented `obtainStyledAttributes`), so `View` never parses our synthetic [AttributeSet].
 * [renderContent] exposes the protected `onDraw` so the [dev.ide.preview.ViewHost] adapter can run the
 * user's drawing (virtual dispatch reaches the user override).
 */
open class BridgeView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context)

    fun renderContent(canvas: android.graphics.Canvas) = onDraw(canvas)
}

// View bases the remapper targets for users subclassing widgets. They extend BridgeView so no framework
// drawing leaks in (a faithful enough base for leaf custom views in a static preview).
open class BridgeViewGroup : BridgeView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
}

open class BridgeTextView : BridgeView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
}

open class BridgeImageView : BridgeView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
}

open class BridgeLinearLayout : BridgeView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
}

open class BridgeFrameLayout : BridgeView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
}
