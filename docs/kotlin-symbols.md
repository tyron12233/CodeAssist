# kotlin-symbols (experimental)

The port probe for `:lang-kotlin-index`. Nothing depends on it.

## Why

`:kotlin-syntax` answered whether Kotlin can be PARSED without a JVM, and `:kotlin-classfile` answered
whether a classpath can be READ without one. Both answered by agreeing with the library they replace: the
parser against the real compiler, the decoder against ASM, kotlin-metadata-jvm and `java.util.zip`.

Neither answers the question that decides the port. A decoder agreeing with ASM about what is IN a class
file is not the same claim as the CONSUMER agreeing about what it MEANS. `:lang-kotlin-index`'s
`JavaBytecode` is four hundred lines of rules about those bytes:

- which members are hidden (private, synthetic, bridge) and which are not
- how a JVM primitive becomes a Kotlin classifier, and why `byte[]` is `ByteArray` but `String[]` is
  `Array<String>`
- when a `MethodParameters` name is real and when the whole list has to be thrown away
- which order members come in
- when a nested name keeps its `$` and when it becomes a dot

Every one of those is a place a rewrite goes wrong without failing, and no oracle at the ASM level can see
any of them. So this module rewrites that layer on `:kotlin-classfile` and diffs it, symbol for symbol,
against the real one.

## What is here

```
Symbols.kt       SymbolKind, Modifier, TypeName, Symbol, JavaShape: the subset of :language-api's model
                 that reading a classpath fills in, ported rather than redesigned
JavaSymbols.kt   the port of JavaBytecode, rule for rule, on ClassFile + JavaSignatures
KotlinSymbols.kt the port of the index's own KotlinMetadata, on the portable @Metadata decoder
TypeRendering.kt how a type is spelled for display: `(A, B) -> R`, not `Function2<A, B, R>`
```

ONE `Symbol` type for both paths, as in the original: bytecode fills some fields and `@Metadata` fills
others, and nothing above this level is supposed to be able to tell which a symbol came from. Splitting it
in two would have made the port neater and the comparison meaningless.

`:lang-kotlin-index` is a **jvmTest** dependency, for the diff and for nothing else. The module itself
depends only on `:kotlin-classfile` and builds for jvm + iosSimulatorArm64 + iosArm64.

## How it is checked

Both implementations run over the same class files, and every field of every symbol is compared: kind, name,
type, modifiers, display signature, own type parameters and their erasure bounds, parameter types, parameter
names, declaring class, deprecation, vararg index. Types are compared rendered, `isTypeParameter` and use-site
projection included, because a `T` that lost the flag renders identically and is not the same type.

- **android.jar (API 37): 6,440 classes and 97,148 symbols identical.** The corpus this layer exists for:
  entirely Java, no Kotlin metadata anywhere, and the biggest single thing on a real classpath.
- **4,199 classes and 27,152 symbols across the 23 jars on the test classpath, identical.**
- **946 Kotlin classes of `kotlin-stdlib` read identically as plain bytecode.** The index reads both shapes
  for a Kotlin class, the metadata for its Kotlin signatures and the bytecode for what the JVM actually has,
  so the Java path has to agree on Kotlin classes too.

And for the `@Metadata` path, against the real `KotlinMetadata`, with everything bytecode erases:

- **kotlin-stdlib: 852 units and 8,871 symbols identical.**
- **The whole test classpath: 2,125 units and 17,446 symbols identical.**
- Compared field by field, including the ones that only exist on this path: extension receiver and its type
  arguments, `internal`, `inline`, `infix`, `suspend`, per-parameter defaults, sibling bound names, use-site
  projections, declaration-site variance, companion, sealed subclasses, enum entries, and type aliases with
  their expansions.
- One real class file is embedded in `commonTest` and decoded **on the iOS simulator** through both paths,
  asserting the answers a completion list would use.

## Two bugs the probe found in the original

Both are reproduced here rather than fixed, because a port is measured against what it ports and a
unilateral improvement shows up as a difference. Both are asserted, so the day `:lang-kotlin-index` fixes
one, this fails and says why.

- **The erased and generic paths disagree about nested names.** The erased path normalises the binary `$` to
  a dot, with a comment explaining that an assignment check false-flagged a mismatch without it. The
  generic-signature path does not. So the same nested type has two spellings depending on whether the member
  that mentions it happened to carry a signature attribute.
- **An inner class of a generic outer merges its arguments.** The signature grammar writes a non-static inner
  class of a generic outer as `LOuter<A;B;>.Inner<C;>;`, and the original's visitor accumulates arguments
  across `visitClassType` and `visitInnerClassType` into one list, reporting them all against the innermost
  name. `Outer<A, B>.Inner<C>` becomes a single type called `Outer.Inner` carrying three arguments, on a type
  declared with one. Rare, because it needs a generic outer AND a non-static inner, which is why it has
  survived; 8 classes on the test classpath hit it.

## Two things only the Kotlin path could have taught

- **A type parameter is referred to by id OR by name, and both come out of the same compiler.**
  `type_parameter` (7) and `type_parameter_name` (9) are alternatives, not versions. A reader that handles
  only the id silently resolves a real `V` to the fallback `T`, and an extension whose receiver is a bare
  parameter loses the parameter entirely, so it can never be bound to the actual receiver. Two of the three
  failures in this port were this, in different places.
- **A `suspend (...) -> R` is stored in a shape it is not read back in.** The metadata records it as a plain
  `FunctionN` with a trailing `Continuation<R>` parameter and an erased `Any` return, with only a flag to say
  what it really is. Rebuilding `kotlin.SuspendFunctionN` from that is what makes a binary suspend type
  compare equal to a source one.

## What this says about the port

Both halves of `:lang-kotlin-index`'s symbol layer port with no loss: the rules move across unchanged, and
the only structural difference is that ASM's push API becomes a pull walk. What remains is not decoding.
`:language-api`'s `TypeRef` / `SymbolKind` / `Modifier` and `:index-api`'s `IndexExtension` are the model all
of this would be read INTO, and both are JVM-only modules; making them reachable from common code is a change
to production structure.
