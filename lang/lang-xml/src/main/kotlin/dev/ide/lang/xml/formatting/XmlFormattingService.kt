package dev.ide.lang.xml.formatting

import dev.ide.lang.dom.TextRange
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.vfs.VirtualFile

/**
 * Reformat Code for XML (see [XmlFormatter]). The [FormattingService] entry points use the default
 * [XmlFormatOptions]; the host, which resolves the XML Code Style profile per reformat, calls the overloads
 * that take them.
 */
class XmlFormattingService : FormattingService {

    override suspend fun format(file: VirtualFile, text: CharSequence, style: FormatStyle): List<DocumentEdit> =
        format(file, text, style, XmlFormatOptions())

    override suspend fun formatRange(
        file: VirtualFile,
        text: CharSequence,
        range: TextRange,
        style: FormatStyle,
    ): List<DocumentEdit> = formatRange(file, text, range, style, XmlFormatOptions())

    fun format(file: VirtualFile, text: CharSequence, style: FormatStyle, options: XmlFormatOptions): List<DocumentEdit> =
        XmlFormatter.reformat(file.path, text.toString(), style, options)

    fun formatRange(
        file: VirtualFile,
        text: CharSequence,
        range: TextRange,
        style: FormatStyle,
        options: XmlFormatOptions,
    ): List<DocumentEdit> {
        val src = text.toString()
        val start = range.start.coerceIn(0, src.length)
        return XmlFormatter.reformat(file.path, src, style, options, start, range.end.coerceIn(start, src.length))
    }
}
