package dev.ide.build.engine

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Rewrites sensitive call sites in user/library bytecode to [Guards] trampolines, so the running program's
 * network / file / reflection / process calls are mediated by the permission [PermissionBroker]. Companion
 * to [ExitGuard] (exit) — applied by `JavaDexTask` to everything on the run's classpath.
 *
 * Two kinds of rewrite:
 *  - **method calls** (static & instance): `invoke* Owner.m(args)` → `invokestatic Guards.g(receiver?, args)`
 *    (same operands, same result — verifier-safe, no frame change).
 *  - **constructors**: `new Owner; dup; args; invokespecial Owner.<init>(args)` → `args; invokestatic
 *    Guards.factory(args): Owner` (a factory that checks then constructs). The `new`+`dup` are removed; to
 *    stay frame-safe (the original stack-map is kept and only maxs recomputed), a construction is rewritten
 *    ONLY when its `new`→`<init>` region is straight-line (no stack-map frame between) — otherwise it's left
 *    alone. Construction sites are matched LIFO, which naturally skips `super()`/`this()` (no preceding `new`).
 *
 * Covers a curated set of common entry points over instrumented code; native calls or uninstrumented
 * paths can still bypass it.
 */
object SandboxGuard {
    private const val GUARDS = "dev/ide/build/engine/Guards"

    /** Instance vs static affects which opcodes match; [guardDesc] is the exact descriptor of the [Guards] target. */
    private class M(val guardName: String, val guardDesc: String, val static: Boolean)

    // key: "owner#name#desc"
    private val METHODS: Map<String, M> = buildMap {
        // network
        put("java/net/URL#openConnection#()Ljava/net/URLConnection;", M("openConnection", "(Ljava/net/URL;)Ljava/net/URLConnection;", false))
        put("java/net/URL#openConnection#(Ljava/net/Proxy;)Ljava/net/URLConnection;", M("openConnection", "(Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;", false))
        put("java/net/URL#openStream#()Ljava/io/InputStream;", M("openStream", "(Ljava/net/URL;)Ljava/io/InputStream;", false))
        put("java/net/Socket#connect#(Ljava/net/SocketAddress;)V", M("socketConnect", "(Ljava/net/Socket;Ljava/net/SocketAddress;)V", false))
        put("java/net/Socket#connect#(Ljava/net/SocketAddress;I)V", M("socketConnect", "(Ljava/net/Socket;Ljava/net/SocketAddress;I)V", false))
        put("java/net/InetAddress#getByName#(Ljava/lang/String;)Ljava/net/InetAddress;", M("getByName", "(Ljava/lang/String;)Ljava/net/InetAddress;", true))
        put("java/net/InetAddress#getAllByName#(Ljava/lang/String;)[Ljava/net/InetAddress;", M("getAllByName", "(Ljava/lang/String;)[Ljava/net/InetAddress;", true))
        // file read (Files.*)
        put("java/nio/file/Files#newInputStream#(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;", M("filesNewInputStream", "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;", true))
        put("java/nio/file/Files#newBufferedReader#(Ljava/nio/file/Path;)Ljava/io/BufferedReader;", M("filesNewBufferedReader", "(Ljava/nio/file/Path;)Ljava/io/BufferedReader;", true))
        put("java/nio/file/Files#newBufferedReader#(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/io/BufferedReader;", M("filesNewBufferedReader", "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/io/BufferedReader;", true))
        put("java/nio/file/Files#readAllBytes#(Ljava/nio/file/Path;)[B", M("filesReadAllBytes", "(Ljava/nio/file/Path;)[B", true))
        put("java/nio/file/Files#readAllLines#(Ljava/nio/file/Path;)Ljava/util/List;", M("filesReadAllLines", "(Ljava/nio/file/Path;)Ljava/util/List;", true))
        put("java/nio/file/Files#readAllLines#(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/util/List;", M("filesReadAllLines", "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/util/List;", true))
        put("java/nio/file/Files#readString#(Ljava/nio/file/Path;)Ljava/lang/String;", M("filesReadString", "(Ljava/nio/file/Path;)Ljava/lang/String;", true))
        put("java/nio/file/Files#readString#(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/lang/String;", M("filesReadString", "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/lang/String;", true))
        put("java/nio/file/Files#lines#(Ljava/nio/file/Path;)Ljava/util/stream/Stream;", M("filesLines", "(Ljava/nio/file/Path;)Ljava/util/stream/Stream;", true))
        put("java/nio/file/Files#lines#(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/util/stream/Stream;", M("filesLines", "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/util/stream/Stream;", true))
        // file write (Files.*)
        put("java/nio/file/Files#newOutputStream#(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/OutputStream;", M("filesNewOutputStream", "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/OutputStream;", true))
        put("java/nio/file/Files#newBufferedWriter#(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/BufferedWriter;", M("filesNewBufferedWriter", "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/BufferedWriter;", true))
        put("java/nio/file/Files#newBufferedWriter#(Ljava/nio/file/Path;Ljava/nio/charset/Charset;[Ljava/nio/file/OpenOption;)Ljava/io/BufferedWriter;", M("filesNewBufferedWriter", "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;[Ljava/nio/file/OpenOption;)Ljava/io/BufferedWriter;", true))
        put("java/nio/file/Files#write#(Ljava/nio/file/Path;[B[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;", M("filesWrite", "(Ljava/nio/file/Path;[B[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;", true))
        put("java/nio/file/Files#write#(Ljava/nio/file/Path;Ljava/lang/Iterable;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;", M("filesWrite", "(Ljava/nio/file/Path;Ljava/lang/Iterable;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;", true))
        put("java/nio/file/Files#writeString#(Ljava/nio/file/Path;Ljava/lang/CharSequence;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;", M("filesWriteString", "(Ljava/nio/file/Path;Ljava/lang/CharSequence;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;", true))
        put("java/nio/file/Files#delete#(Ljava/nio/file/Path;)V", M("filesDelete", "(Ljava/nio/file/Path;)V", true))
        put("java/nio/file/Files#deleteIfExists#(Ljava/nio/file/Path;)Z", M("filesDeleteIfExists", "(Ljava/nio/file/Path;)Z", true))
        // reflection
        put("java/lang/Class#forName#(Ljava/lang/String;)Ljava/lang/Class;", M("classForName", "(Ljava/lang/String;)Ljava/lang/Class;", true))
        put("java/lang/Class#forName#(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", M("classForName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", true))
        put("java/lang/reflect/Method#invoke#(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", M("invoke", "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false))
        put("java/lang/reflect/Constructor#newInstance#([Ljava/lang/Object;)Ljava/lang/Object;", M("newInstance", "(Ljava/lang/reflect/Constructor;[Ljava/lang/Object;)Ljava/lang/Object;", false))
        // setAccessible — receiver may be statically typed as any AccessibleObject subtype; one Guards target.
        for (owner in listOf("java/lang/reflect/AccessibleObject", "java/lang/reflect/Method", "java/lang/reflect/Field", "java/lang/reflect/Constructor")) {
            put("$owner#setAccessible#(Z)V", M("setAccessible", "(Ljava/lang/reflect/AccessibleObject;Z)V", false))
        }
        put("java/lang/reflect/AccessibleObject#setAccessible#([Ljava/lang/reflect/AccessibleObject;Z)V", M("setAccessibleAll", "([Ljava/lang/reflect/AccessibleObject;Z)V", true))
        // process / exec
        put("java/lang/Runtime#exec#(Ljava/lang/String;)Ljava/lang/Process;", M("exec", "(Ljava/lang/Runtime;Ljava/lang/String;)Ljava/lang/Process;", false))
        put("java/lang/Runtime#exec#([Ljava/lang/String;)Ljava/lang/Process;", M("exec", "(Ljava/lang/Runtime;[Ljava/lang/String;)Ljava/lang/Process;", false))
        put("java/lang/Runtime#exec#(Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;", M("exec", "(Ljava/lang/Runtime;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;", false))
        put("java/lang/Runtime#exec#([Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;", M("exec", "(Ljava/lang/Runtime;[Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;", false))
        put("java/lang/ProcessBuilder#start#()Ljava/lang/Process;", M("pbStart", "(Ljava/lang/ProcessBuilder;)Ljava/lang/Process;", false))
    }

    // key: "owner#ctorDesc" -> factory name in Guards (factory desc derived: ctorArgs -> Lowner;)
    private val CTORS: Map<String, String> = buildMap {
        for (d in listOf("(Ljava/io/File;)V", "(Ljava/lang/String;)V")) put("java/io/FileInputStream#$d", "newFileInputStream")
        for (d in listOf("(Ljava/io/File;)V", "(Ljava/lang/String;)V")) put("java/io/FileReader#$d", "newFileReader")
        for (d in listOf("(Ljava/io/File;)V", "(Ljava/io/File;Z)V", "(Ljava/lang/String;)V", "(Ljava/lang/String;Z)V")) put("java/io/FileOutputStream#$d", "newFileOutputStream")
        for (d in listOf("(Ljava/io/File;)V", "(Ljava/io/File;Z)V", "(Ljava/lang/String;)V", "(Ljava/lang/String;Z)V")) put("java/io/FileWriter#$d", "newFileWriter")
        for (d in listOf("(Ljava/io/File;Ljava/lang/String;)V", "(Ljava/lang/String;Ljava/lang/String;)V")) put("java/io/RandomAccessFile#$d", "newRandomAccessFile")
        for (d in listOf("(Ljava/lang/String;I)V", "(Ljava/net/InetAddress;I)V", "(Ljava/lang/String;ILjava/net/InetAddress;I)V", "(Ljava/net/InetAddress;ILjava/net/InetAddress;I)V")) put("java/net/Socket#$d", "newSocket")
    }
    private val CTOR_OWNERS: Set<String> = CTORS.keys.mapTo(HashSet()) { it.substringBefore('#') }

    /** Distinctive constant-pool markers — a class without any of these can't hit a guarded call. */
    private val MARKERS = listOf(
        "java/net/URL", "java/net/Socket", "java/net/InetAddress", "java/nio/file/Files",
        "java/io/FileInputStream", "java/io/FileOutputStream", "java/io/FileReader", "java/io/FileWriter",
        "java/io/RandomAccessFile", "java/lang/Runtime", "java/lang/ProcessBuilder",
        "java/lang/reflect/Method", "java/lang/reflect/Constructor", "java/lang/reflect/AccessibleObject",
        "java/lang/reflect/Field", "forName",
    )

    fun instrument(bytes: ByteArray): ByteArray {
        if (!mightMatch(bytes)) return bytes
        val reader = ClassReader(bytes)
        val node = ClassNode()
        reader.accept(node, 0)
        var changed = false
        for (mn in node.methods) if (rewrite(mn)) changed = true
        if (!changed) return bytes
        // COMPUTE_MAXS only (the original frames are kept; rewrites preserve them — ctor rewrites are confined
        // to frame-free straight-line regions). No COMPUTE_FRAMES → no class-loading for supertype merges.
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    private fun rewrite(mn: MethodNode): Boolean {
        val insns = mn.instructions
        if (insns.size() == 0) return false
        var changed = false

        // --- constructor rewrites: LIFO-match new↔<init>, only when frame-free between them ---
        class Pend(val newNode: TypeInsnNode, val dup: AbstractInsnNode, var frameSeen: Boolean)
        val stack = ArrayDeque<Pend>()
        val ctorHits = ArrayList<Triple<MethodInsnNode, TypeInsnNode, AbstractInsnNode>>()
        var n: AbstractInsnNode? = insns.first
        while (n != null) {
            when {
                n is FrameNode -> stack.forEach { it.frameSeen = true }
                n is TypeInsnNode && n.opcode == Opcodes.NEW && n.desc in CTOR_OWNERS -> {
                    val d = realNext(n)
                    if (d != null && d.opcode == Opcodes.DUP) stack.addLast(Pend(n, d, false))
                }
                n is MethodInsnNode && n.opcode == Opcodes.INVOKESPECIAL && n.name == "<init>" && "${n.owner}#${n.desc}" in CTORS -> {
                    val top = stack.lastOrNull()
                    if (top != null && top.newNode.desc == n.owner) {
                        stack.removeLast()
                        if (!top.frameSeen) ctorHits.add(Triple(n, top.newNode, top.dup))
                    }
                }
            }
            n = n.next
        }
        for ((special, newNode, dup) in ctorHits) {
            val factory = CTORS["${special.owner}#${special.desc}"]!!
            val factoryDesc = special.desc.removeSuffix("V") + "L${special.owner};"
            insns.set(special, MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, factory, factoryDesc, false))
            insns.remove(newNode)
            insns.remove(dup)
            changed = true
        }

        // --- method-call rewrites ---
        for (insn in insns.toArray()) {
            if (insn !is MethodInsnNode || insn.owner == GUARDS) continue
            val m = METHODS["${insn.owner}#${insn.name}#${insn.desc}"] ?: continue
            val matches = if (m.static) insn.opcode == Opcodes.INVOKESTATIC
            else insn.opcode == Opcodes.INVOKEVIRTUAL || insn.opcode == Opcodes.INVOKEINTERFACE
            if (matches) {
                insns.set(insn, MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, m.guardName, m.guardDesc, false))
                changed = true
            }
        }
        return changed
    }

    /** Next real (non-pseudo) instruction — skips labels, line numbers, and frames (opcode < 0). */
    private fun realNext(insn: AbstractInsnNode): AbstractInsnNode? {
        var c = insn.next
        while (c != null && c.opcode < 0) c = c.next
        return c
    }

    private fun mightMatch(bytes: ByteArray): Boolean {
        val s = String(bytes, Charsets.ISO_8859_1)
        return MARKERS.any { it in s }
    }
}
