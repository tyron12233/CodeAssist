# kotlin-classfile (experimental)

Reading `.class` files and the Kotlin metadata inside them, with no JVM. Nothing depends on it.

## Why

Two things pinned Kotlin completion to the JVM. The first was the parser, and `:kotlin-syntax` settles that by
vendoring the compiler's own. This is the other: `:lang-kotlin-index` is 3,244 lines that are genuinely
compiler-free, but they decode class files with **ASM** and `@kotlin.Metadata` with **kotlin-metadata-jvm**,
and neither artifact exists off the JVM.

Unlike the parser there is nothing upstream to borrow. `core/metadata` and `libraries/kotlinx-metadata` are
both `kotlin("jvm")` and sit on JVM protobuf. So this is a real port — but a much smaller one than it looks,
for two reasons.

The **ASM surface actually used** is narrow: a `ClassReader` with `SKIP_CODE`/`SKIP_FRAMES`/`SKIP_DEBUG` and
four visitors. Method bodies are exactly what an index does not read.

And the **34,545 lines of generated protobuf** are not the thing to port. `metadata.proto` is 709 lines and is
the source of truth; the wire format can be walked without a schema; and only about a dozen of its messages
matter. So what is needed is a wire reader plus the field numbers, not a code generator.

## What is here

752 lines, `commonMain`, no dependencies, building for jvm + iosSimulatorArm64 + iosArm64.

```
ProtoReader        the protobuf wire format: varints, tags, length-delimited fields
MetadataEncoding   undoes the String[] packing @Metadata uses to smuggle protobuf through an annotation
ClassFile          constant pool, class/super/interfaces, and finding the @Metadata annotation
KotlinMetadata     the dozen messages an index reads, with every field number cited to metadata.proto
```

## How it is checked

Hand-written cases prove nothing for a decoder of someone else's binary format. It is either right about real
input or it is not, and the failure mode is not an exception: a wrong field number reads a **different** field
and returns something plausible. So the suite is an oracle against the JVM libraries this module exists to
replace, over this build's own compiled output.

- **353 class files agree with ASM** on name, superclass and interfaces.
- **326 Kotlin classes, 2,653 declarations agree with kotlin-metadata-jvm.**

Plus the negative cases that matter: a Java class must report no Kotlin metadata, and garbage must return null
rather than throw, because a classpath contains jars built by anything and an index that throws stops indexing.

## Three things worth knowing

- **`@Metadata` has two encodings**, chosen by a marker character: current compilers use UTF-8 mode (each
  char's low byte is the byte), older ones pack eight bits into seven so every char survives the class file's
  modified UTF-8. A third, oldest form has no marker at all and is also 8-to-7 — which is why the marker check
  cannot end in an `else`.
- **Modified UTF-8 is not UTF-8.** A NUL is two bytes and a supplementary character is two three-byte
  surrogates, so `decodeToString` gets both wrong on names that contain either.
- **`long` and `double` take two constant-pool slots.** Miss it and every index after is off by one, which
  reads as corrupt names rather than as an off-by-one.

## Not done yet

The spike reads structure and declaration NAMES. An index also needs signatures, types, type parameters,
flags (visibility, modality, `suspend`, `inline`), and the JVM signature extensions from `jvm_metadata.proto`.
Those are more field numbers against the same machinery rather than new machinery.
