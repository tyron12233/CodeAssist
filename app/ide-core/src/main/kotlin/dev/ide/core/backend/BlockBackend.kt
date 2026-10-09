package dev.ide.core.backend

import dev.ide.block.BlockEdit
import dev.ide.block.BlockId
import dev.ide.block.BlockNode
import dev.ide.block.BlockPart
import dev.ide.block.BlockRef
import dev.ide.block.BlockTemplate
import dev.ide.block.Delete
import dev.ide.block.DeleteRange
import dev.ide.block.InsertArgument
import dev.ide.block.InsertTemplate
import dev.ide.block.RemoveArgument
import dev.ide.block.Move
import dev.ide.block.MoveRange
import dev.ide.block.ReplaceWithText
import dev.ide.block.SetField
import dev.ide.block.SlotCategory
import dev.ide.block.SlotRef
import dev.ide.block.Wrap
import dev.ide.block.WrapRange
import dev.ide.core.BackendContext
import dev.ide.ui.backend.BlockService
import dev.ide.ui.backend.UiBlockEdit
import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.backend.UiBlockPart
import dev.ide.ui.backend.UiTextEdit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [BlockService] over the engine's projectional editor: project the buffer into a block tree and compile a
 *  block edit back to surgical text edits. Runs on the serialized engine dispatcher. */
internal class BlockBackend(private val ctx: BackendContext) : BlockService {

    override fun blocksEnabled(): Boolean = ctx.servicesOrNull?.blocks?.enabled ?: false

    override suspend fun projectBlocks(path: String, text: String): UiBlockNode? =
        withContext(ctx.engineDispatcher) {
            ctx.services.blocks.projectBlocks(
                Paths.get(path), text
            )
        }?.let { toUiBlock(it.root) }

    override suspend fun applyBlockEdit(
        path: String, text: String, edit: UiBlockEdit
    ): List<UiTextEdit> {
        val blockEdit: BlockEdit = when (edit) {
            is UiBlockEdit.SetField -> SetField(
                BlockRef(BlockId(edit.blockId)), edit.role, edit.text
            )

            is UiBlockEdit.ReplaceSlot -> ReplaceWithText(
                SlotRef(
                    BlockId(edit.blockId), edit.slotIndex
                ), edit.text
            )

            is UiBlockEdit.DeleteBlock -> Delete(BlockRef(BlockId(edit.blockId)))
            is UiBlockEdit.InsertTemplate -> InsertTemplate(
                SlotRef(BlockId(edit.ownerBlockId), edit.slotIndex, edit.index),
                BlockTemplate(
                    label = "insert", category = SlotCategory.STATEMENT, defaultText = edit.text
                ),
            )

            is UiBlockEdit.WrapInIf -> Wrap(
                BlockRef(BlockId(edit.blockId)),
                BlockTemplate(
                    label = "if",
                    category = SlotCategory.STATEMENT,
                    defaultText = "if (true) {\n${BlockTemplate.PLACEHOLDER}\n}"
                ),
            )

            is UiBlockEdit.MoveBlock -> Move(
                BlockRef(BlockId(edit.blockId)),
                SlotRef(BlockId(edit.toOwnerBlockId), edit.toSlotIndex, edit.toIndex),
            )

            is UiBlockEdit.MoveRange -> MoveRange(
                BlockRef(BlockId(edit.blockId)), edit.count,
                SlotRef(BlockId(edit.toOwnerBlockId), edit.toSlotIndex, edit.toIndex),
            )

            is UiBlockEdit.DeleteRange -> DeleteRange(BlockRef(BlockId(edit.blockId)), edit.count)
            is UiBlockEdit.InsertArgument -> InsertArgument(BlockRef(BlockId(edit.blockId)), edit.link, edit.index, edit.text)
            is UiBlockEdit.RemoveArgument -> RemoveArgument(BlockRef(BlockId(edit.blockId)), edit.slotIndex)
            is UiBlockEdit.WrapRange -> WrapRange(
                BlockRef(BlockId(edit.blockId)), edit.count,
                BlockTemplate(
                    label = "wrap",
                    category = SlotCategory.STATEMENT,
                    defaultText = edit.template.replace(UiBlockEdit.BODY_MARKER, BlockTemplate.PLACEHOLDER),
                ),
            )
        }
        return withContext(ctx.engineDispatcher) {
            ctx.services.blocks.computeBlockEdit(
                Paths.get(path), text, blockEdit
            )
        }.map { UiTextEdit(it.offset, it.offset + it.oldLength, it.newText.toString()) }
    }

    override suspend fun loadScratchStacks(path: String): String? = withContext(Dispatchers.IO) {
        scratchFile(path)?.takeIf { Files.isRegularFile(it) }?.let { runCatching { it.readText() }.getOrNull() }
    }

    override suspend fun saveScratchStacks(path: String, data: String?) = withContext(Dispatchers.IO) {
        val file = scratchFile(path) ?: return@withContext
        runCatching {
            if (data.isNullOrBlank()) Files.deleteIfExists(file)
            else {
                Files.createDirectories(file.parent)
                file.writeText(data)
            }
        }
        Unit
    }

    /**
     * Where [path]'s scratch stacks live: under the project's `.platform/blocks/`, mirroring the file's path
     * relative to the workspace root, so they never sit in a source root or reach a build. Null for a file
     * outside the workspace.
     */
    private fun scratchFile(path: String): Path? {
        val root = ctx.servicesOrNull?.workspaceRoot?.toAbsolutePath()?.normalize() ?: return null
        val file = Paths.get(path).toAbsolutePath().normalize()
        if (!file.startsWith(root)) return null
        return root.resolve(".platform/blocks").resolve(root.relativize(file).toString() + ".stacks")
    }

    /** Map a framework [BlockNode] subtree onto the UI's neutral [UiBlockNode] DTO. */
    private fun toUiBlock(b: BlockNode): UiBlockNode = UiBlockNode(
        id = b.id.value,
        kind = b.kind.id,
        label = b.label,
        category = b.kind.id,
        start = b.range.start,
        end = b.range.end,
        parts = b.parts.map { part ->
            when (part) {
                is BlockPart.Field -> UiBlockPart.Field(
                    role = part.field.role,
                    text = part.field.text,
                    editable = part.field.editable,
                    start = part.field.range?.start ?: -1,
                    end = part.field.range?.end ?: -1,
                )

                is BlockPart.Slot -> UiBlockPart.Slot(
                    category = part.slot.category.name,
                    multiple = part.slot.multiple,
                    start = part.slot.range.start,
                    end = part.slot.range.end,
                    children = part.slot.children.map(::toUiBlock),
                    valueKind = part.slot.valueKind.name.lowercase(),
                )
            }
        },
        valueKind = b.valueKind.name.lowercase(),
    )
}
