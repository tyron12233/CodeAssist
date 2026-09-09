package dev.ide.core.services

import dev.ide.block.BLOCK_MAPPING_EP
import dev.ide.block.BlockEdit
import dev.ide.block.BlockTree
import dev.ide.block.impl.BlockProjectionEngine
import dev.ide.core.EngineContext
import dev.ide.lang.incremental.DocumentEdit
import java.nio.file.Path

/**
 * WORKSPACE-scoped engine service: the projectional (block) editor. Projects a buffer into a [BlockTree] and
 * compiles a [BlockEdit] back to surgical document edits. Carved out of [dev.ide.core.IdeServices].
 *
 * A block tree is a projection of the SAME tolerant DOM the editor/analyzer use, so this service depends only
 * on [EngineContext.parse] (the shared parse primitive) plus its own [BlockProjectionEngine] (built from the
 * `platform.blockMapping` EP, so a plugin can contribute its own decomposition). Projection is deterministic
 * for identical text, so the ids it assigns round-trip a block edit (which re-projects the same text to
 * resolve them) without holding any session state.
 */
internal class BlockService(private val ctx: EngineContext) {

    private val mappings = ctx.platform.extensions.extensions(BLOCK_MAPPING_EP)
    private val engine = BlockProjectionEngine(mappings)

    /**
     * Whether the block editor is available — i.e. at least one block-mapping is registered on
     * [BLOCK_MAPPING_EP]. Disabling the `blocks` built-in plugin drops the only mapping, so this goes false and
     * the UI hides the Blocks view-mode segment. With no mapping the projection would be an inert opaque tree.
     */
    val enabled: Boolean get() = mappings.isNotEmpty()

    /** Project [file]'s live buffer [text] into a [BlockTree], or null if [file] is outside the project. */
    fun projectBlocks(file: Path, text: String): BlockTree? =
        ctx.parse(file, text)?.let { engine.project(it) }

    /** Compile a [BlockEdit] against [file]'s buffer [text] into surgical document edits (empty if N/A). */
    fun computeBlockEdit(file: Path, text: String, edit: BlockEdit): List<DocumentEdit> {
        val tree = projectBlocks(file, text) ?: return emptyList()
        return engine.computeEdit(tree, text, edit)
    }
}
