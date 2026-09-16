# kotlin-classfile (experimental)

Reading a classpath with no JVM: jars, `.class` files, and the Kotlin metadata inside them. No ASM, no
kotlin-metadata-jvm, no `java.util.zip`. Nothing depends on it.

## Why

Two things pinned Kotlin completion to the JVM. The first was the parser, and `:kotlin-syntax` settles that by
vendoring the compiler's own. This is the other: `:lang-kotlin-index` is 3,244 lines that are genuinely
compiler-free, but they decode class files with **ASM** and `@kotlin.Metadata` with **kotlin-metadata-jvm**,
and neither artifact exists off the JVM.

Unlike the parser there is nothing upstream to borrow. `core/metadata` and `libraries/kotlinx-metadata` are
both `kotlin("jvm")` and sit on JVM protobuf. So this is a real port, but a much smaller one than it looks,
for two reasons.

The **ASM surface actually used** is narrow: a `ClassReader` with `SKIP_CODE`/`SKIP_FRAMES` and four
visitors, plus `Type` and `SignatureReader`. Method BODIES are exactly what an index does not read, which is
most of a class file.

And the **34,545 lines of generated protobuf** are not the thing to port. `metadata.proto` is 709 lines and is
the source of truth; the wire format can be walked without a schema; and only about a dozen of its messages
matter. So what is needed is a wire reader plus the field numbers, not a code generator.

## What is here

2,516 lines, `commonMain`, no dependencies, building for jvm + iosSimulatorArm64 + iosArm64.

```
the archive
  ByteSource       random access to bytes; a zip is read back to front, so a stream will not do
  ZipArchive       end record, zip64, central directory, local headers, stored and deflated entries
  Inflate          DEFLATE (RFC 1951): bit reader, canonical Huffman, back-references
  Crc32            the archive's own statement about whether our decompressor got it right

the class file
  ClassFile        constant pool, class shape, fields, methods, InnerClasses, and the @Metadata annotation
  JavaSignatures   descriptors and generic signatures: the JVMS 4.3 and 4.7.9.1 grammars

the Kotlin metadata
  ProtoReader      the protobuf wire format: varints, tags, length-delimited fields
  MetadataEncoding undoes the String[] packing @Metadata uses to smuggle protobuf through an annotation
  JvmNameResolver  the string table: every name in the protobuf is an index, and resolving one is not a lookup
  KotlinMetadata   the dozen messages an index reads, with every field number cited to metadata.proto
  KotlinFlags      the packed bit field: visibility, modality, kind, suspend/inline/infix/var/const/lateinit
  JvmDescriptors   Kotlin class names to JVM descriptors, for the signatures the compiler declines to write
```

## How it is checked

Hand-written cases prove nothing for a decoder of someone else's binary format. It is either right about real
input or it is not, and the failure mode is not an exception: a wrong field number reads a **different** field
and returns something plausible. So the suite is an oracle against the JVM libraries this module exists to
replace, over two corpora: this build's own compiled output, and the whole `kotlin-stdlib` jar.

Against this build's output:

- **353 class files agree with ASM** on name, superclass and interfaces.
- **326 Kotlin classes, 2,653 declarations agree with kotlin-metadata-jvm.**
- **906 function signatures agree**, rendered whole: receiver, parameter names and types, return type,
  generics and nullability.
- **312 classes and 2,652 members agree on flags**: visibility, modality, class kind, member kind, and
  `data`/`inner`/`value`/`fun interface`/`suspend`/`inline`/`infix`/`operator`/`tailrec`/`var`/`const`/
  `lateinit`/`expect`/`external`.
- **6,361 members agree on their JVM signature**: method name and descriptor, backing field, getter, setter.

Against `kotlin-stdlib`, because one compiler's output is a soft corpus and the jar an index actually has to
read is not:

- **990 class files agree with ASM**; **660 classes, 8,189 members and 11,053 signatures agree with
  kotlin-metadata-jvm**, with nothing in the jar the library would read and this would not.

Against the Java half, which is most of a real classpath and all of `android.jar`:

- **2,677 classes and 30,759 members** across the 15 jars on the test classpath, and **6,440 classes and
  97,562 members of `android.jar`**, agree with ASM on access flags, name, superclass, interfaces, every
  field and method, their descriptors and signatures, their `MethodParameters` names, and `InnerClasses`.
- **128,362 descriptors** and **1,431 class, 12,318 method and 2,354 field generic signatures** parse to
  what ASM's `Type` and `SignatureReader` read.

Against `java.util.zip`, over every jar in reach:

- **17,571 entries across 16 archives, 59 MB, inflated byte for byte**, with names, sizes, methods and CRCs
  agreeing entry for entry. Plus zip64 (70,000 entries, where the 32-bit fields saturate), the stored method,
  empty entries, and a deliberately corrupted entry that must come back null rather than plausible.

Plus the negative cases that matter: a Java class must report no Kotlin metadata, garbage must return null
rather than throw, and no entry anywhere in the stdlib may throw, because a classpath contains jars built by
anything, and an index that dies on one entry stops indexing.

## Three things worth knowing

- **`@Metadata` has two encodings**, chosen by a marker character: current compilers use UTF-8 mode (each
  char's low byte is the byte), older ones pack eight bits into seven so every char survives the class file's
  modified UTF-8. A third, oldest form has no marker at all and is also 8-to-7, which is why the marker check
  cannot end in an `else`.
- **Modified UTF-8 is not UTF-8.** A NUL is two bytes and a supplementary character is two three-byte
  surrogates, so `decodeToString` gets both wrong on names that contain either.
- **`long` and `double` take two constant-pool slots.** Miss it and every index after is off by one, which
  reads as corrupt names rather than as an off-by-one.

## Four things the oracle caught that no hand-written test would have

Each rendered as a perfectly plausible answer, which is the whole argument for diffing against the real
library rather than against expectations someone typed.

- **`position += readInt()` is wrong in Kotlin.** The left operand is read BEFORE the right is evaluated, so
  the position captured predates the length varint and the assignment discards that advance. One byte short,
  no error, and everything after it reads as plausible nonsense. It surfaced as `unknown wire type 6` a
  hundred bytes later.
- **A type parameter carries both an id and a name, and the id wins.** Taking whichever field arrived last
  preferred the name and silently produced a different type.
- **A LOCAL class name is marked with a leading dot.** That convention is the only thing separating a class
  declared inside a function from a top-level class of the same name, and `local_name` in the string table is
  what records it.
- **`k=4` is the multi-file class FACADE, not one of its parts** (`k=5` is). A facade's `d1` is the only one
  that is not protobuf at all: it holds the plain internal names of the part classes, never passed through
  the encoding step, so reading it as a message takes a length prefix out of the middle of a class name and
  then runs off the end of the array. This one DID throw, which is the exception that proves the rule: it
  only threw because the corpus finally contained a `@JvmMultifileClass`, and every module in this build is
  written by one compiler in one style. Widening the corpus to the stdlib found it in the first run.

## Two things that are not field numbers

- **Flags are a bit field with no schema.** `metadata.proto` gives one `int32`; the layout exists only as a
  chain in the compiler, where each field is placed immediately after the previous one and an enum field is
  as wide as `values.size - 1` needs. So every offset is a consequence of every offset above it, and adding
  a seventh visibility to Kotlin would move all of them. `KotlinFlags` spells the widths as `bitWidth(N)`
  against the enum sizes rather than as constants, so that they move together. Getting one wrong reports
  `private` for a `public` function.
- **Most JVM signatures are not written down.** The compiler records one only when it is *not* derivable
  from the Kotlin declaration, and expects the reader to recompute the rest, so the bulk of the work is the
  reconstruction, not the decode. An extension function's receiver is its first JVM parameter; leave it out
  and the descriptor is one argument short and names no method that exists.

## Speed

`Inflate` decodes bit by bit against the canonical code counts rather than through a lookup table. That is
the shape whose correctness can be read off the spec, and the table is where the bugs live. The cost is
measured rather than assumed: inflating all 1,002 entries of `kotlin-stdlib` takes around **45 ms against
java.util.zip's under 20 ms, so 2 to 3x run to run**, and java.util.zip is native zlib. A table-driven
decoder is the fix if that ever matters, and the oracle above is what would make trying it safe.

## Not done yet

Nothing blocking, and the remaining gaps are all things neither this build's compiler nor `android.jar`
exercises: annotations on declarations, Kotlin's `TypeTable` indirection, and zip names in CP437 rather than
UTF-8 (every jar writes ASCII, where the two agree). Encryption and multi-disk archives are not gaps to fill
later: an archive needing either is not a classpath entry this can index, and pretending otherwise would be
worse than returning null.

What this does NOT yet do is BE an index. `:lang-kotlin-index` still holds the symbol model, the persistence
format (`DataInput`/`DataOutput`) and the file walking (`java.nio.file`), and porting those is a dependency
decision rather than more decoding.
