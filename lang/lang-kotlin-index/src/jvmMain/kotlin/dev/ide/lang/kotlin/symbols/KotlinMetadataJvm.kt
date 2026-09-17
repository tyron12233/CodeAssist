package dev.ide.lang.kotlin.symbols

import dev.ide.kotlin.classfile.KotlinMetadataAnnotation
import dev.ide.kotlin.classfile.KotlinMetadata as MetadataReader

/**
 * [KotlinMetadata.jvmNameIndex] for a caller that holds a LOADED class's `@kotlin.Metadata` rather than a
 * `.class` file's bytes.
 *
 * The interpreter is such a caller: it reflects over a class it has already loaded, so the annotation is an
 * instance, not a byte range to parse. An extension rather than an overload because the object it extends
 * lives in `commonMain`, and `kotlin.Metadata` is a JVM annotation type that cannot be named there.
 */
fun KotlinMetadata.jvmNameIndex(metadata: Metadata): Map<String, Set<String>>? {
    val annotation = KotlinMetadataAnnotation(
        kind = metadata.kind,
        metadataVersion = metadata.metadataVersion,
        data1 = metadata.data1,
        data2 = metadata.data2,
        extraString = metadata.extraString,
        packageName = metadata.packageName,
    )
    return jvmNameIndex(MetadataReader.read(annotation))
}
