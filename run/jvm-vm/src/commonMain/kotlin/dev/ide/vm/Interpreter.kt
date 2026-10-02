package dev.ide.vm

/** How deep interpreted calls may nest before the VM throws `StackOverflowError` instead of crashing the host. */
internal const val MAX_DEPTH = 1800

/** A resolved `Methodref`/`InterfaceMethodref`, with a one-entry inline cache for virtual dispatch. */
internal class MethodSite(
    val ownerName: String,
    val name: String,
    val descriptor: String,
    val argWords: Int,
    val retWords: Int,
) {
    val key = name + descriptor
    var resolved: VmMethod? = null
    var lastClass: VmClass? = null
    var lastTarget: VmMethod? = null
}

private fun Vm.methodSite(cls: VmClass, index: Int, isStatic: Boolean): MethodSite {
    cls.resolved[index]?.let { return it as MethodSite }
    val pool = cls.pool!!
    val desc = pool.memberDescriptor(index)
    var words = if (isStatic) 0 else 1
    for (p in parameterDescriptors(desc)) words += wordsOf(kindOf(p[0]))
    val site = MethodSite(pool.memberOwner(index), pool.memberName(index), desc, words, wordsOf(kindOf(returnDescriptor(desc)[0])))
    cls.resolved[index] = site
    return site
}

private fun Vm.fieldRef(cls: VmClass, index: Int): VmField {
    cls.resolved[index]?.let { return it as VmField }
    val pool = cls.pool!!
    val owner = loadClass(pool.memberOwner(index))
    val name = pool.memberName(index)
    val desc = pool.memberDescriptor(index)
    val f = findField(owner, name, desc)
        ?: if (owner.isNative) throw VmUnsupportedException("the JDK floor has no field ${owner.name}.$name:$desc")
        else throwVm("java/lang/NoSuchFieldError", "${owner.javaName}.$name")
    cls.resolved[index] = f
    return f
}

internal fun Vm.classRef(cls: VmClass, index: Int): VmClass {
    cls.resolved[index]?.let { return it as VmClass }
    val c = loadClass(cls.pool!!.className(index))
    cls.resolved[index] = c
    return c
}

private fun Vm.resolveStatic(site: MethodSite): VmMethod {
    site.resolved?.let { return it }
    val owner = loadClass(site.ownerName)
    val m = findMethod(owner, site.key)
        ?: if (owner.isNative) throw VmUnsupportedException("the JDK floor does not implement ${owner.name}.${site.key}")
        else throwVm("java/lang/NoSuchMethodError", "${owner.javaName}.${site.key}")
    site.resolved = m
    return m
}

private fun Vm.selectFor(site: MethodSite, receiver: Any): VmMethod {
    val rc = classOf(receiver)
    if (rc === site.lastClass) return site.lastTarget!!
    val m = selectVirtual(rc, site.key)
    if (m == null || m.isAbstract) {
        if (m == null && hasNativeAncestor(rc)) {
            throw VmUnsupportedException("the JDK floor does not implement ${site.key} for ${rc.name}")
        }
        if (m != null && m.owner.isNative) throw VmUnsupportedException("the JDK floor does not implement ${m.owner.name}.${site.key} (on ${rc.name})")
        throwVm("java/lang/AbstractMethodError", "${rc.javaName}.${site.key}")
    }
    site.lastClass = rc
    site.lastTarget = m
    return m
}

private fun hasNativeAncestor(cls: VmClass): Boolean {
    var c: VmClass? = cls
    while (c != null) { if (c.isNative && c.name != "java/lang/Object") return true; c = c.superClass }
    return false
}

private fun Vm.findHandler(m: VmMethod, pc: Int, ex: VmObject): Int {
    for (h in m.handlers) {
        if (pc < h.startPc || pc >= h.endPc) continue
        if (h.catchType == 0) return h.handlerPc
        if (isInstance(ex, classRef(m.owner, h.catchType))) return h.handlerPc
    }
    return -1
}

private fun s2(code: ByteArray, at: Int): Int = (code[at].toInt() shl 8) or (code[at + 1].toInt() and 0xFF)
private fun u2(code: ByteArray, at: Int): Int = ((code[at].toInt() and 0xFF) shl 8) or (code[at + 1].toInt() and 0xFF)
private fun s4(code: ByteArray, at: Int): Int =
    (code[at].toInt() shl 24) or ((code[at + 1].toInt() and 0xFF) shl 16) or
        ((code[at + 2].toInt() and 0xFF) shl 8) or (code[at + 3].toInt() and 0xFF)

private fun fl(v: Long): Float = Float.fromBits(v.toInt())
private fun fb(v: Float): Long = v.toRawBits().toLong()
private fun db(v: Long): Double = Double.fromBits(v)

private fun Vm.arrayLength(a: Any?): Int = when (a) {
    is VmRefArray -> a.data.size
    is IntArray -> a.size
    is ByteArray -> a.size
    is CharArray -> a.size
    is LongArray -> a.size
    is FloatArray -> a.size
    is DoubleArray -> a.size
    is BooleanArray -> a.size
    is ShortArray -> a.size
    null -> throwNpe("arraylength")
    else -> error("not an array: $a")
}

private fun Vm.multiArray(arrayDescriptor: String, counts: IntArray, level: Int): Any {
    val component = arrayDescriptor.substring(1)
    val n = counts[level]
    val arr = newArray(component, n)
    if (level + 1 < counts.size && arr is VmRefArray) {
        for (i in 0 until n) arr.data[i] = multiArray(component, counts, level + 1)
    }
    return arr
}

/** Copies of the uninitialized value in this frame become the constructed value (JVMS 4.10.2.4). */
private fun replaceUninit(r: Array<Any?>, from: Int, to: Int, placeholder: Any, value: Any?) {
    for (k in from until to) if (r[k] === placeholder) r[k] = value
}

/**
 * Runs [m]'s bytecode in the frame at [bp] of [t]. Its arguments are already in the frame's first locals;
 * its result is written back at [bp].
 */
internal fun Vm.execute(m: VmMethod, t: VmThread, bp: Int) {
    val code = m.code!!
    val cls = m.owner
    val P = t.p
    val R = t.r
    val frameEnd = bp + m.maxLocals + m.maxStack
    if (frameEnd + 4 >= P.size || t.depth >= MAX_DEPTH) throwVm("java/lang/StackOverflowError", null)
    t.depth++
    val savedTop = t.top
    t.top = frameEnd + 2
    var sp = bp + m.maxLocals
    var pc = 0
    var ip = 0
    var steps = 0L
    try {
        while (true) {
            try {
                while (true) {
                    ip = pc
                    steps++
                    when (code[pc].toInt() and 0xFF) {
                        0x00 -> pc++ // nop
                        0x01 -> { R[sp++] = null; pc++ }
                        0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 -> { P[sp++] = ((code[pc].toInt() and 0xFF) - 3).toLong(); pc++ }
                        0x09, 0x0a -> { P[sp] = ((code[pc].toInt() and 0xFF) - 9).toLong(); sp += 2; pc++ }
                        0x0b, 0x0c, 0x0d -> { P[sp++] = fb(((code[pc].toInt() and 0xFF) - 0x0b).toFloat()); pc++ }
                        0x0e, 0x0f -> { P[sp] = ((code[pc].toInt() and 0xFF) - 0x0e).toDouble().toRawBits(); sp += 2; pc++ }
                        0x10 -> { P[sp++] = code[pc + 1].toLong(); pc += 2 } // bipush
                        0x11 -> { P[sp++] = s2(code, pc + 1).toLong(); pc += 3 } // sipush
                        0x12 -> { sp = ldc(cls, code[pc + 1].toInt() and 0xFF, P, R, sp); pc += 2 }
                        0x13 -> { sp = ldc(cls, u2(code, pc + 1), P, R, sp); pc += 3 }
                        0x14 -> { P[sp] = cls.pool!!.wide[u2(code, pc + 1)]; sp += 2; pc += 3 } // ldc2_w
                        0x15, 0x17 -> { P[sp++] = P[bp + (code[pc + 1].toInt() and 0xFF)]; pc += 2 } // iload, fload
                        0x16, 0x18 -> { P[sp] = P[bp + (code[pc + 1].toInt() and 0xFF)]; sp += 2; pc += 2 } // lload, dload
                        0x19 -> { R[sp++] = R[bp + (code[pc + 1].toInt() and 0xFF)]; pc += 2 } // aload
                        0x1a, 0x1b, 0x1c, 0x1d -> { P[sp++] = P[bp + (code[pc].toInt() and 0xFF) - 0x1a]; pc++ }
                        0x1e, 0x1f, 0x20, 0x21 -> { P[sp] = P[bp + (code[pc].toInt() and 0xFF) - 0x1e]; sp += 2; pc++ }
                        0x22, 0x23, 0x24, 0x25 -> { P[sp++] = P[bp + (code[pc].toInt() and 0xFF) - 0x22]; pc++ }
                        0x26, 0x27, 0x28, 0x29 -> { P[sp] = P[bp + (code[pc].toInt() and 0xFF) - 0x26]; sp += 2; pc++ }
                        0x2a, 0x2b, 0x2c, 0x2d -> { R[sp++] = R[bp + (code[pc].toInt() and 0xFF) - 0x2a]; pc++ }
                        0x2e -> { val a = R[sp - 2] as IntArray? ?: throwNpe("iaload"); P[sp - 2] = a[P[sp - 1].toInt()].toLong(); sp--; pc++ }
                        0x2f -> { val a = R[sp - 2] as LongArray? ?: throwNpe("laload"); P[sp - 2] = a[P[sp - 1].toInt()]; pc++ }
                        0x30 -> { val a = R[sp - 2] as FloatArray? ?: throwNpe("faload"); P[sp - 2] = fb(a[P[sp - 1].toInt()]); sp--; pc++ }
                        0x31 -> { val a = R[sp - 2] as DoubleArray? ?: throwNpe("daload"); P[sp - 2] = a[P[sp - 1].toInt()].toRawBits(); pc++ }
                        0x32 -> { val a = R[sp - 2] as VmRefArray? ?: throwNpe("aaload"); R[sp - 2] = a.data[P[sp - 1].toInt()]; sp--; pc++ }
                        0x33 -> {
                            val i = P[sp - 1].toInt()
                            P[sp - 2] = when (val a = R[sp - 2]) {
                                is ByteArray -> a[i].toLong()
                                is BooleanArray -> if (a[i]) 1L else 0L
                                null -> throwNpe("baload")
                                else -> error("baload on $a")
                            }
                            sp--; pc++
                        }
                        0x34 -> { val a = R[sp - 2] as CharArray? ?: throwNpe("caload"); P[sp - 2] = a[P[sp - 1].toInt()].code.toLong(); sp--; pc++ }
                        0x35 -> { val a = R[sp - 2] as ShortArray? ?: throwNpe("saload"); P[sp - 2] = a[P[sp - 1].toInt()].toLong(); sp--; pc++ }
                        0x36, 0x38 -> { P[bp + (code[pc + 1].toInt() and 0xFF)] = P[--sp]; pc += 2 } // istore, fstore
                        0x37, 0x39 -> { sp -= 2; P[bp + (code[pc + 1].toInt() and 0xFF)] = P[sp]; pc += 2 } // lstore, dstore
                        0x3a -> { R[bp + (code[pc + 1].toInt() and 0xFF)] = R[--sp]; pc += 2 } // astore
                        0x3b, 0x3c, 0x3d, 0x3e -> { P[bp + (code[pc].toInt() and 0xFF) - 0x3b] = P[--sp]; pc++ }
                        0x3f, 0x40, 0x41, 0x42 -> { sp -= 2; P[bp + (code[pc].toInt() and 0xFF) - 0x3f] = P[sp]; pc++ }
                        0x43, 0x44, 0x45, 0x46 -> { P[bp + (code[pc].toInt() and 0xFF) - 0x43] = P[--sp]; pc++ }
                        0x47, 0x48, 0x49, 0x4a -> { sp -= 2; P[bp + (code[pc].toInt() and 0xFF) - 0x47] = P[sp]; pc++ }
                        0x4b, 0x4c, 0x4d, 0x4e -> { R[bp + (code[pc].toInt() and 0xFF) - 0x4b] = R[--sp]; pc++ }
                        0x4f -> { val a = R[sp - 3] as IntArray? ?: throwNpe("iastore"); a[P[sp - 2].toInt()] = P[sp - 1].toInt(); sp -= 3; pc++ }
                        0x50 -> { val a = R[sp - 4] as LongArray? ?: throwNpe("lastore"); a[P[sp - 3].toInt()] = P[sp - 2]; sp -= 4; pc++ }
                        0x51 -> { val a = R[sp - 3] as FloatArray? ?: throwNpe("fastore"); a[P[sp - 2].toInt()] = fl(P[sp - 1]); sp -= 3; pc++ }
                        0x52 -> { val a = R[sp - 4] as DoubleArray? ?: throwNpe("dastore"); a[P[sp - 3].toInt()] = db(P[sp - 2]); sp -= 4; pc++ }
                        0x53 -> { val a = R[sp - 3] as VmRefArray? ?: throwNpe("aastore"); a.data[P[sp - 2].toInt()] = R[sp - 1]; sp -= 3; pc++ }
                        0x54 -> {
                            val i = P[sp - 2].toInt()
                            when (val a = R[sp - 3]) {
                                is ByteArray -> a[i] = P[sp - 1].toInt().toByte()
                                is BooleanArray -> a[i] = (P[sp - 1].toInt() and 1) != 0
                                null -> throwNpe("bastore")
                                else -> error("bastore on $a")
                            }
                            sp -= 3; pc++
                        }
                        0x55 -> { val a = R[sp - 3] as CharArray? ?: throwNpe("castore"); a[P[sp - 2].toInt()] = P[sp - 1].toInt().toChar(); sp -= 3; pc++ }
                        0x56 -> { val a = R[sp - 3] as ShortArray? ?: throwNpe("sastore"); a[P[sp - 2].toInt()] = P[sp - 1].toInt().toShort(); sp -= 3; pc++ }
                        0x57 -> { sp--; pc++ } // pop
                        0x58 -> { sp -= 2; pc++ } // pop2
                        0x59 -> { P[sp] = P[sp - 1]; R[sp] = R[sp - 1]; sp++; pc++ } // dup
                        0x5a -> { // dup_x1
                            P[sp] = P[sp - 1]; R[sp] = R[sp - 1]
                            P[sp - 1] = P[sp - 2]; R[sp - 1] = R[sp - 2]
                            P[sp - 2] = P[sp]; R[sp - 2] = R[sp]
                            sp++; pc++
                        }
                        0x5b -> { // dup_x2
                            P[sp] = P[sp - 1]; R[sp] = R[sp - 1]
                            P[sp - 1] = P[sp - 2]; R[sp - 1] = R[sp - 2]
                            P[sp - 2] = P[sp - 3]; R[sp - 2] = R[sp - 3]
                            P[sp - 3] = P[sp]; R[sp - 3] = R[sp]
                            sp++; pc++
                        }
                        0x5c -> { // dup2
                            P[sp] = P[sp - 2]; R[sp] = R[sp - 2]
                            P[sp + 1] = P[sp - 1]; R[sp + 1] = R[sp - 1]
                            sp += 2; pc++
                        }
                        0x5d -> { // dup2_x1: a b c -> b c a b c
                            P[sp + 1] = P[sp - 1]; R[sp + 1] = R[sp - 1]
                            P[sp] = P[sp - 2]; R[sp] = R[sp - 2]
                            P[sp - 1] = P[sp - 3]; R[sp - 1] = R[sp - 3]
                            P[sp - 2] = P[sp + 1]; R[sp - 2] = R[sp + 1]
                            P[sp - 3] = P[sp]; R[sp - 3] = R[sp]
                            sp += 2; pc++
                        }
                        0x5e -> { // dup2_x2: a b c d -> c d a b c d
                            P[sp + 1] = P[sp - 1]; R[sp + 1] = R[sp - 1]
                            P[sp] = P[sp - 2]; R[sp] = R[sp - 2]
                            P[sp - 1] = P[sp - 3]; R[sp - 1] = R[sp - 3]
                            P[sp - 2] = P[sp - 4]; R[sp - 2] = R[sp - 4]
                            P[sp - 3] = P[sp + 1]; R[sp - 3] = R[sp + 1]
                            P[sp - 4] = P[sp]; R[sp - 4] = R[sp]
                            sp += 2; pc++
                        }
                        0x5f -> { // swap
                            val pv = P[sp - 1]; val rv = R[sp - 1]
                            P[sp - 1] = P[sp - 2]; R[sp - 1] = R[sp - 2]
                            P[sp - 2] = pv; R[sp - 2] = rv
                            pc++
                        }
                        0x60 -> { P[sp - 2] = (P[sp - 2].toInt() + P[sp - 1].toInt()).toLong(); sp--; pc++ }
                        0x61 -> { P[sp - 4] = P[sp - 4] + P[sp - 2]; sp -= 2; pc++ }
                        0x62 -> { P[sp - 2] = fb(fl(P[sp - 2]) + fl(P[sp - 1])); sp--; pc++ }
                        0x63 -> { P[sp - 4] = (db(P[sp - 4]) + db(P[sp - 2])).toRawBits(); sp -= 2; pc++ }
                        0x64 -> { P[sp - 2] = (P[sp - 2].toInt() - P[sp - 1].toInt()).toLong(); sp--; pc++ }
                        0x65 -> { P[sp - 4] = P[sp - 4] - P[sp - 2]; sp -= 2; pc++ }
                        0x66 -> { P[sp - 2] = fb(fl(P[sp - 2]) - fl(P[sp - 1])); sp--; pc++ }
                        0x67 -> { P[sp - 4] = (db(P[sp - 4]) - db(P[sp - 2])).toRawBits(); sp -= 2; pc++ }
                        0x68 -> { P[sp - 2] = (P[sp - 2].toInt() * P[sp - 1].toInt()).toLong(); sp--; pc++ }
                        0x69 -> { P[sp - 4] = P[sp - 4] * P[sp - 2]; sp -= 2; pc++ }
                        0x6a -> { P[sp - 2] = fb(fl(P[sp - 2]) * fl(P[sp - 1])); sp--; pc++ }
                        0x6b -> { P[sp - 4] = (db(P[sp - 4]) * db(P[sp - 2])).toRawBits(); sp -= 2; pc++ }
                        0x6c -> {
                            val b = P[sp - 1].toInt()
                            if (b == 0) throwVm("java/lang/ArithmeticException", "/ by zero")
                            P[sp - 2] = (P[sp - 2].toInt() / b).toLong(); sp--; pc++
                        }
                        0x6d -> {
                            val b = P[sp - 2]
                            if (b == 0L) throwVm("java/lang/ArithmeticException", "/ by zero")
                            P[sp - 4] = P[sp - 4] / b; sp -= 2; pc++
                        }
                        0x6e -> { P[sp - 2] = fb(fl(P[sp - 2]) / fl(P[sp - 1])); sp--; pc++ }
                        0x6f -> { P[sp - 4] = (db(P[sp - 4]) / db(P[sp - 2])).toRawBits(); sp -= 2; pc++ }
                        0x70 -> {
                            val b = P[sp - 1].toInt()
                            if (b == 0) throwVm("java/lang/ArithmeticException", "/ by zero")
                            P[sp - 2] = (P[sp - 2].toInt() % b).toLong(); sp--; pc++
                        }
                        0x71 -> {
                            val b = P[sp - 2]
                            if (b == 0L) throwVm("java/lang/ArithmeticException", "/ by zero")
                            P[sp - 4] = P[sp - 4] % b; sp -= 2; pc++
                        }
                        0x72 -> { P[sp - 2] = fb(fl(P[sp - 2]) % fl(P[sp - 1])); sp--; pc++ }
                        0x73 -> { P[sp - 4] = (db(P[sp - 4]) % db(P[sp - 2])).toRawBits(); sp -= 2; pc++ }
                        0x74 -> { P[sp - 1] = (-P[sp - 1].toInt()).toLong(); pc++ }
                        0x75 -> { P[sp - 2] = -P[sp - 2]; pc++ }
                        0x76 -> { P[sp - 1] = fb(-fl(P[sp - 1])); pc++ }
                        0x77 -> { P[sp - 2] = (-db(P[sp - 2])).toRawBits(); pc++ }
                        0x78 -> { P[sp - 2] = (P[sp - 2].toInt() shl P[sp - 1].toInt()).toLong(); sp--; pc++ }
                        0x79 -> { P[sp - 3] = P[sp - 3] shl P[sp - 1].toInt(); sp--; pc++ }
                        0x7a -> { P[sp - 2] = (P[sp - 2].toInt() shr P[sp - 1].toInt()).toLong(); sp--; pc++ }
                        0x7b -> { P[sp - 3] = P[sp - 3] shr P[sp - 1].toInt(); sp--; pc++ }
                        0x7c -> { P[sp - 2] = (P[sp - 2].toInt() ushr P[sp - 1].toInt()).toLong(); sp--; pc++ }
                        0x7d -> { P[sp - 3] = P[sp - 3] ushr P[sp - 1].toInt(); sp--; pc++ }
                        0x7e -> { P[sp - 2] = (P[sp - 2].toInt() and P[sp - 1].toInt()).toLong(); sp--; pc++ }
                        0x7f -> { P[sp - 4] = P[sp - 4] and P[sp - 2]; sp -= 2; pc++ }
                        0x80 -> { P[sp - 2] = (P[sp - 2].toInt() or P[sp - 1].toInt()).toLong(); sp--; pc++ }
                        0x81 -> { P[sp - 4] = P[sp - 4] or P[sp - 2]; sp -= 2; pc++ }
                        0x82 -> { P[sp - 2] = (P[sp - 2].toInt() xor P[sp - 1].toInt()).toLong(); sp--; pc++ }
                        0x83 -> { P[sp - 4] = P[sp - 4] xor P[sp - 2]; sp -= 2; pc++ }
                        0x84 -> { // iinc
                            val slot = bp + (code[pc + 1].toInt() and 0xFF)
                            P[slot] = (P[slot].toInt() + code[pc + 2]).toLong(); pc += 3
                        }
                        0x85 -> { sp++; pc++ } // i2l: an int is already held sign-extended
                        0x86 -> { P[sp - 1] = fb(P[sp - 1].toInt().toFloat()); pc++ }
                        0x87 -> { P[sp - 1] = P[sp - 1].toInt().toDouble().toRawBits(); sp++; pc++ }
                        0x88 -> { P[sp - 2] = P[sp - 2].toInt().toLong(); sp--; pc++ }
                        0x89 -> { P[sp - 2] = fb(P[sp - 2].toFloat()); sp--; pc++ }
                        0x8a -> { P[sp - 2] = P[sp - 2].toDouble().toRawBits(); pc++ }
                        0x8b -> { P[sp - 1] = fl(P[sp - 1]).toInt().toLong(); pc++ }
                        0x8c -> { P[sp - 1] = fl(P[sp - 1]).toLong(); sp++; pc++ }
                        0x8d -> { P[sp - 1] = fl(P[sp - 1]).toDouble().toRawBits(); sp++; pc++ }
                        0x8e -> { P[sp - 2] = db(P[sp - 2]).toInt().toLong(); sp--; pc++ }
                        0x8f -> { P[sp - 2] = db(P[sp - 2]).toLong(); pc++ }
                        0x90 -> { P[sp - 2] = fb(db(P[sp - 2]).toFloat()); sp--; pc++ }
                        0x91 -> { P[sp - 1] = P[sp - 1].toInt().toByte().toLong(); pc++ }
                        0x92 -> { P[sp - 1] = (P[sp - 1].toInt() and 0xFFFF).toLong(); pc++ }
                        0x93 -> { P[sp - 1] = P[sp - 1].toInt().toShort().toLong(); pc++ }
                        0x94 -> { val a = P[sp - 4]; val b = P[sp - 2]; P[sp - 4] = (if (a > b) 1L else if (a < b) -1L else 0L); sp -= 3; pc++ }
                        0x95, 0x96 -> {
                            val a = fl(P[sp - 2]); val b = fl(P[sp - 1])
                            P[sp - 2] = when {
                                a > b -> 1L; a < b -> -1L; a == b -> 0L
                                else -> if ((code[pc].toInt() and 0xFF) == 0x95) -1L else 1L
                            }
                            sp--; pc++
                        }
                        0x97, 0x98 -> {
                            val a = db(P[sp - 4]); val b = db(P[sp - 2])
                            P[sp - 4] = when {
                                a > b -> 1L; a < b -> -1L; a == b -> 0L
                                else -> if ((code[pc].toInt() and 0xFF) == 0x97) -1L else 1L
                            }
                            sp -= 3; pc++
                        }
                        0x99 -> { pc += if (P[--sp].toInt() == 0) s2(code, pc + 1) else 3 }
                        0x9a -> { pc += if (P[--sp].toInt() != 0) s2(code, pc + 1) else 3 }
                        0x9b -> { pc += if (P[--sp].toInt() < 0) s2(code, pc + 1) else 3 }
                        0x9c -> { pc += if (P[--sp].toInt() >= 0) s2(code, pc + 1) else 3 }
                        0x9d -> { pc += if (P[--sp].toInt() > 0) s2(code, pc + 1) else 3 }
                        0x9e -> { pc += if (P[--sp].toInt() <= 0) s2(code, pc + 1) else 3 }
                        0x9f -> { sp -= 2; pc += if (P[sp].toInt() == P[sp + 1].toInt()) s2(code, pc + 1) else 3 }
                        0xa0 -> { sp -= 2; pc += if (P[sp].toInt() != P[sp + 1].toInt()) s2(code, pc + 1) else 3 }
                        0xa1 -> { sp -= 2; pc += if (P[sp].toInt() < P[sp + 1].toInt()) s2(code, pc + 1) else 3 }
                        0xa2 -> { sp -= 2; pc += if (P[sp].toInt() >= P[sp + 1].toInt()) s2(code, pc + 1) else 3 }
                        0xa3 -> { sp -= 2; pc += if (P[sp].toInt() > P[sp + 1].toInt()) s2(code, pc + 1) else 3 }
                        0xa4 -> { sp -= 2; pc += if (P[sp].toInt() <= P[sp + 1].toInt()) s2(code, pc + 1) else 3 }
                        0xa5 -> { sp -= 2; pc += if (R[sp] === R[sp + 1]) s2(code, pc + 1) else 3 }
                        0xa6 -> { sp -= 2; pc += if (R[sp] !== R[sp + 1]) s2(code, pc + 1) else 3 }
                        0xa7 -> pc += s2(code, pc + 1) // goto
                        0xa8, 0xa9 -> throw VmUnsupportedException("jsr/ret in ${m}")
                        0xaa -> { // tableswitch
                            val q = (ip + 4) and 3.inv()
                            val key = P[--sp].toInt()
                            val low = s4(code, q + 4)
                            val high = s4(code, q + 8)
                            pc = ip + if (key < low || key > high) s4(code, q) else s4(code, q + 12 + (key - low) * 4)
                        }
                        0xab -> { // lookupswitch
                            val q = (ip + 4) and 3.inv()
                            val key = P[--sp].toInt()
                            var lo = 0
                            var hi = s4(code, q + 4) - 1
                            var target = s4(code, q)
                            while (lo <= hi) {
                                val mid = (lo + hi) ushr 1
                                val match = s4(code, q + 8 + mid * 8)
                                if (match < key) lo = mid + 1
                                else if (match > key) hi = mid - 1
                                else { target = s4(code, q + 12 + mid * 8); break }
                            }
                            pc = ip + target
                        }
                        0xac, 0xae -> { P[bp] = P[sp - 1]; return }
                        0xad, 0xaf -> { P[bp] = P[sp - 2]; return }
                        0xb0 -> { R[bp] = R[sp - 1]; return }
                        0xb1 -> return
                        0xb2 -> { // getstatic
                            val f = fieldRef(cls, u2(code, pc + 1))
                            val owner = f.owner
                            if (owner.initState != INIT_DONE) ensureInitialized(owner)
                            if (f.isRef) R[sp++] = owner.staticRefs[f.slot]
                            else { P[sp] = owner.staticPrims[f.slot]; sp += f.words }
                            pc += 3
                        }
                        0xb3 -> { // putstatic
                            val f = fieldRef(cls, u2(code, pc + 1))
                            val owner = f.owner
                            if (owner.initState != INIT_DONE) ensureInitialized(owner)
                            if (f.isRef) owner.staticRefs[f.slot] = R[--sp]
                            else { sp -= f.words; owner.staticPrims[f.slot] = P[sp] }
                            pc += 3
                        }
                        0xb4 -> { // getfield
                            val f = fieldRef(cls, u2(code, pc + 1))
                            val o = R[sp - 1] as VmObject? ?: throwNpe("getfield ${f.name}")
                            if (f.isRef) R[sp - 1] = o.refs[f.slot]
                            else { P[sp - 1] = o.prims[f.slot]; sp += f.words - 1 }
                            pc += 3
                        }
                        0xb5 -> { // putfield
                            val f = fieldRef(cls, u2(code, pc + 1))
                            if (f.isRef) {
                                val o = R[sp - 2] as VmObject? ?: throwNpe("putfield ${f.name}")
                                o.refs[f.slot] = R[sp - 1]
                                sp -= 2
                            } else {
                                sp -= f.words
                                val o = R[sp - 1] as VmObject? ?: throwNpe("putfield ${f.name}")
                                o.prims[f.slot] = P[sp]
                                sp--
                            }
                            pc += 3
                        }
                        0xb6, 0xb9 -> { // invokevirtual, invokeinterface
                            val site = methodSite(cls, u2(code, pc + 1), false)
                            val base = sp - site.argWords
                            val recv = R[base] ?: throwNpe("${site.ownerName}.${site.name}")
                            val target = selectFor(site, recv)
                            t.top = frameEnd + 2
                            invoke(target, base)
                            sp = base + site.retWords
                            pc += if ((code[pc].toInt() and 0xFF) == 0xb6) 3 else 5
                        }
                        0xb7 -> { // invokespecial
                            val site = methodSite(cls, u2(code, pc + 1), false)
                            val target = site.resolved ?: resolveStatic(site)
                            val base = sp - site.argWords
                            val recv = R[base] ?: throwNpe("${site.ownerName}.${site.name}")
                            invoke(target, base)
                            if (recv is Uninit) replaceUninit(R, bp, base, recv, R[base])
                            sp = base + site.retWords
                            pc += 3
                        }
                        0xb8 -> { // invokestatic
                            val site = methodSite(cls, u2(code, pc + 1), true)
                            val target = site.resolved ?: resolveStatic(site)
                            if (target.owner.initState != INIT_DONE) ensureInitialized(target.owner)
                            val base = sp - site.argWords
                            invoke(target, base)
                            sp = base + site.retWords
                            pc += 3
                        }
                        0xba -> { // invokedynamic
                            val index = u2(code, pc + 1)
                            val site = cls.resolved[index] as IndySite? ?: linkIndy(cls, index)
                            sp = site.call(this, t, sp)
                            pc += 5
                        }
                        0xbb -> { // new
                            val c = classRef(cls, u2(code, pc + 1))
                            if (c.initState != INIT_DONE) ensureInitialized(c)
                            if (c.isAbstract && !c.valueBacked) throwVm("java/lang/InstantiationError", c.javaName)
                            R[sp++] = if (c.valueBacked) Uninit(c) else VmObject(c)
                            pc += 3
                        }
                        0xbc -> { // newarray
                            val desc = when (code[pc + 1].toInt()) {
                                4 -> "Z"; 5 -> "C"; 6 -> "F"; 7 -> "D"; 8 -> "B"; 9 -> "S"; 10 -> "I"; else -> "J"
                            }
                            R[sp - 1] = newArray(desc, P[sp - 1].toInt())
                            pc += 2
                        }
                        0xbd -> { // anewarray
                            val c = classRef(cls, u2(code, pc + 1))
                            val n = P[sp - 1].toInt()
                            if (n < 0) throwVm("java/lang/NegativeArraySizeException", n.toString())
                            val arrayName = if (c.isArray) "[${c.name}" else "[L${c.name};"
                            R[sp - 1] = VmRefArray(loadClass(arrayName), arrayOfNulls(n))
                            pc += 3
                        }
                        0xbe -> { P[sp - 1] = arrayLength(R[sp - 1]).toLong(); pc++ }
                        0xbf -> { // athrow
                            val ex = R[sp - 1] as VmObject? ?: throwNpe("throw null")
                            // A rethrow (a `finally`, a catch-and-rethrow) keeps the frames the exception already
                            // unwound, less the frame that caught it, which this unwind adds again.
                            val vt = VmThrow(ex)
                            (ex.native as? ThrowableState)?.trace?.let { if (it.size > 1) vt.trace.addAll(it.dropLast(1)) }
                            throw vt
                        }
                        0xc0 -> { // checkcast
                            val o = R[sp - 1]
                            if (o != null) {
                                val c = classRef(cls, u2(code, pc + 1))
                                if (!isInstance(o, c)) throwVm("java/lang/ClassCastException", "class ${classOf(o).javaName} cannot be cast to class ${c.javaName}")
                            }
                            pc += 3
                        }
                        0xc1 -> { // instanceof
                            val o = R[sp - 1]
                            P[sp - 1] = if (o != null && isInstance(o, classRef(cls, u2(code, pc + 1)))) 1L else 0L
                            pc += 3
                        }
                        0xc2, 0xc3 -> { if (R[--sp] == null) throwNpe("monitor"); pc++ }
                        0xc4 -> { // wide
                            val op = code[pc + 1].toInt() and 0xFF
                            val slot = bp + u2(code, pc + 2)
                            when (op) {
                                0x15, 0x17 -> P[sp++] = P[slot]
                                0x16, 0x18 -> { P[sp] = P[slot]; sp += 2 }
                                0x19 -> R[sp++] = R[slot]
                                0x36, 0x38 -> P[slot] = P[--sp]
                                0x37, 0x39 -> { sp -= 2; P[slot] = P[sp] }
                                0x3a -> R[slot] = R[--sp]
                                0x84 -> P[slot] = (P[slot].toInt() + s2(code, pc + 4)).toLong()
                                else -> throw VmUnsupportedException("wide $op")
                            }
                            pc += if (op == 0x84) 6 else 4
                        }
                        0xc5 -> { // multianewarray
                            val c = classRef(cls, u2(code, pc + 1))
                            val dims = code[pc + 3].toInt() and 0xFF
                            val counts = IntArray(dims) { P[sp - dims + it].toInt() }
                            for (n in counts) if (n < 0) throwVm("java/lang/NegativeArraySizeException", n.toString())
                            sp -= dims
                            R[sp++] = multiArray(c.name, counts, 0)
                            pc += 4
                        }
                        0xc6 -> { pc += if (R[--sp] == null) s2(code, pc + 1) else 3 }
                        0xc7 -> { pc += if (R[--sp] != null) s2(code, pc + 1) else 3 }
                        0xc8 -> pc += s4(code, pc + 1)
                        else -> throw VmUnsupportedException("opcode ${code[pc].toInt() and 0xFF} in $m")
                    }
                }
            } catch (e: Throwable) {
                val vt = when (e) {
                    is VmThrow -> e
                    is IndexOutOfBoundsException -> {
                        val op = code[ip].toInt() and 0xFF
                        val name = if (op in 0x2e..0x35 || op in 0x4f..0x56) "java/lang/ArrayIndexOutOfBoundsException"
                        else "java/lang/IndexOutOfBoundsException"
                        VmThrow(newThrowable(name, e.message))
                    }
                    is VmUnsupportedException -> {
                        if (e.vmTrace.size < 40) e.vmTrace.add(frameName(m, ip))
                        throw e
                    }
                    else -> throw e
                }
                val handler = findHandler(m, ip, vt.obj)
                if (handler < 0) {
                    if (vt.trace.size < 64) vt.trace.add(frameName(m, ip))
                    throw vt
                }
                (vt.obj.native as? ThrowableState)?.let { if (it.trace.isEmpty()) it.trace = vt.trace + frameName(m, ip) }
                sp = bp + m.maxLocals
                R[sp++] = vt.obj
                pc = handler
            }
        }
    } finally {
        t.depth--
        t.top = savedTop
        t.steps += steps
    }
}

internal fun frameName(m: VmMethod, pc: Int): String {
    val line = m.lineAt(pc)
    val file = m.owner.sourceFile
    val where = if (file != null && line >= 0) "$file:$line" else "pc $pc"
    return "${javaName(m.owner.name)}.${m.name}($where)"
}

private fun Vm.ldc(cls: VmClass, index: Int, P: LongArray, R: Array<Any?>, sp: Int): Int {
    val pool = cls.pool!!
    when (pool.tags[index].toInt()) {
        ConstantPool.INTEGER, ConstantPool.FLOAT -> P[sp] = pool.a[index].toLong()
        ConstantPool.STRING -> {
            val s = cls.resolved[index] ?: intern(pool.string(index)).also { cls.resolved[index] = it }
            R[sp] = s
        }
        ConstantPool.CLASS -> R[sp] = mirror(classRef(cls, index))
        else -> throw VmUnsupportedException("ldc of constant tag ${pool.tags[index]} in ${cls.name}")
    }
    return sp + 1
}
