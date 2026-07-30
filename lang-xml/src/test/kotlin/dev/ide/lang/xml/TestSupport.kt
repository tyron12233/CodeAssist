package dev.ide.lang.xml

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.testkit.InMemoryVirtualFile
import dev.ide.testkit.TestDocument
import dev.ide.vfs.VirtualFile

/** A bare [VirtualFile] backed only by a path — enough for parser/completion tests. */
typealias FakeFile = InMemoryVirtualFile

/** A [DocumentSnapshot] whose file defaults to a layout path for one-liner parse/completion tests. */
class TestDoc(
    text: CharSequence,
    file: VirtualFile = FakeFile("res/layout/test.xml"),
    version: Long = 1,
) : DocumentSnapshot by TestDocument(text, file, version)

fun parse(xml: String): XmlParsedFile =
    XmlIncrementalParser().parseFull(TestDoc(xml)) as XmlParsedFile
