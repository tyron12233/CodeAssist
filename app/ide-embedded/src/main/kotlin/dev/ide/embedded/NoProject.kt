package dev.ide.embedded

import dev.ide.android.layout.AndroidLayoutModel
import dev.ide.android.layout.ElementRef
import dev.ide.android.layout.LayoutAttributeInfo
import dev.ide.android.layout.LayoutDocument
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutRenderRequest
import dev.ide.android.layout.LayoutRenderResult
import dev.ide.android.layout.LayoutRenderer
import dev.ide.android.layout.LayoutValueCompletion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What answers before a project is open: every call fails with the same sentence. */
internal object NoProject : LayoutRenderer, AndroidLayoutModel {
    private fun none(): Nothing = error("No project is open in the embedded backend.")

    override val progress: StateFlow<String?> = MutableStateFlow(null)
    override suspend fun render(request: LayoutRenderRequest): LayoutRenderResult = none()
    override fun prepareLibraries(path: String): Boolean = false
    override fun parse(text: String): LayoutDocument = none()
    override suspend fun attributes(path: String, tag: String, parentTag: String?): List<LayoutAttributeInfo> = none()
    override suspend fun completeValue(path: String, text: String, element: ElementRef, attribute: String, fieldText: String, caret: Int): LayoutValueCompletion = none()
    override suspend fun setAttribute(path: String, text: String, element: ElementRef, attribute: String, value: String): List<LayoutEdit> = none()
    override suspend fun removeAttribute(path: String, text: String, element: ElementRef, attribute: String): List<LayoutEdit> = none()
}
