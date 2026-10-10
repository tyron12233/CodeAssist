package dev.ide.android.support.apilevels

import dev.ide.android.support.apilevels.AndroidApiDatabase.ClassRecord
import dev.ide.android.support.apilevels.AndroidApiDatabase.MemberRecord
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Reads a platform's `data/api-versions.xml` (format version 3) into the records [AndroidApiDatabase.write]
 * encodes. A `<class>`/`<method>`/`<field>` carries optional `since`, `deprecated` and `removed` levels; a
 * member without `since` has its class's. Extension-SDK availability (`sdks="..."`) is not kept: the base
 * `since` is what minSdk is compared against.
 *
 * [desugared] holds the methods D8 backports into the app (`java/util/List#of(Ljava/lang/Object;)Ljava/util/List;`),
 * which are flagged so the minSdk check leaves them alone.
 */
object ApiVersionsXml {

    class Result(val platformLevel: Int, val classes: List<ClassRecord>)

    fun parse(input: InputStream, desugared: Set<String> = emptySet()): Result {
        val classes = ArrayList<ClassRecord>()
        var maxSince = 1
        val handler = object : DefaultHandler() {
            var name: String? = null
            var since = 0
            var deprecated = 0
            var removed = 0
            val supers = ArrayList<String>()
            val methods = ArrayList<MemberRecord>()
            val fields = ArrayList<MemberRecord>()

            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                when (qName) {
                    "class" -> {
                        name = attrs.getValue("name")
                        since = level(attrs, "since").coerceAtLeast(1)
                        deprecated = level(attrs, "deprecated")
                        removed = level(attrs, "removed")
                        maxSince = maxOf(maxSince, since)
                        supers.clear(); methods.clear(); fields.clear()
                    }
                    "extends", "implements" -> if (name != null) attrs.getValue("name")?.let(supers::add)
                    "method", "field" -> {
                        val owner = name ?: return
                        val key = attrs.getValue("name") ?: return
                        val memberSince = level(attrs, "since")
                        maxSince = maxOf(maxSince, memberSince)
                        val record = MemberRecord(
                            key, memberSince, level(attrs, "deprecated"), level(attrs, "removed"),
                            desugared = qName == "method" && "$owner#$key" in desugared,
                        )
                        (if (qName == "method") methods else fields).add(record)
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (qName != "class") return
                val owner = name ?: return
                classes += ClassRecord(owner, since, deprecated, removed, supers.toList(), methods.toList(), fields.toList())
                name = null
            }
        }
        SAXParserFactory.newInstance().newSAXParser().parse(input, handler)
        return Result(maxSince, classes)
    }

    /** `36` and `36.1` (a minor SDK, newer platform tables) both read as their major level. */
    private fun level(attrs: Attributes, name: String): Int =
        attrs.getValue(name)?.substringBefore('.')?.toIntOrNull() ?: 0
}
