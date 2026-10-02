package dev.ide.vm.jdk

import dev.ide.vm.*

/**
 * The rest of the floor libraries ask for in passing: logging, the copy-on-write and concurrent
 * collections (plain collections here, as there is one thread), and a latch.
 */
internal fun ClassDefs.registerMisc() {
    define("java/util/logging/Level") {
        val levels = listOf("OFF" to Int.MAX_VALUE, "SEVERE" to 1000, "WARNING" to 900, "INFO" to 800, "CONFIG" to 700,
            "FINE" to 500, "FINER" to 400, "FINEST" to 300, "ALL" to Int.MIN_VALUE)
        for ((n, _) in levels) staticField(n, "Ljava/util/logging/Level;")
        onInit { cls ->
            for ((n, v) in levels) cls.setStaticRef(n, "Ljava/util/logging/Level;", VmObject(cls).also { it.native = n to v })
        }
        method("getName", "()Ljava/lang/String;") { retRef((self().native as Pair<*, *>).first) }
        method("intValue", "()I") { ret((self().native as Pair<*, *>).second as Int) }
        method("toString", "()Ljava/lang/String;") { retRef((self().native as Pair<*, *>).first) }
    }
    define("java/util/logging/Logger") {
        fun Call.log(level: String, message: Any?) {
            if (level == "SEVERE" || level == "WARNING") vm.stdout("[${self().native}] $level: ${vm.vmToString(message)}\n")
        }
        static("getLogger", "(Ljava/lang/String;)Ljava/util/logging/Logger;") {
            retRef(vm.singletons.getOrPut("\u0000logger:${r(0)}") { vm.newVmObject("java/util/logging/Logger").also { it.native = r(0) } })
        }
        static("getAnonymousLogger", "()Ljava/util/logging/Logger;") {
            retRef(vm.newVmObject("java/util/logging/Logger").also { it.native = "anonymous" })
        }
        for (level in listOf("severe", "warning", "info", "config", "fine", "finer", "finest")) {
            method(level, "(Ljava/lang/String;)V") { log(level.uppercase(), r(1)) }
            method(level, "(Ljava/util/function/Supplier;)V") {
                if (level == "severe" || level == "warning") log(level.uppercase(), vm.callVirtual(r(1), "get", "()Ljava/lang/Object;"))
            }
        }
        method("log", "(Ljava/util/logging/Level;Ljava/lang/String;)V") { log(((r(1) as VmObject).native as Pair<*, *>).first as String, r(2)) }
        method("log", "(Ljava/util/logging/Level;Ljava/lang/String;Ljava/lang/Throwable;)V") {
            log(((r(1) as VmObject).native as Pair<*, *>).first as String, "${vm.vmToString(r(2))}: ${vm.vmToString(r(3))}")
        }
        method("isLoggable", "(Ljava/util/logging/Level;)Z") { ret((((r(1) as VmObject).native as Pair<*, *>).second as Int) >= 900) }
        method("setLevel", "(Ljava/util/logging/Level;)V") {}
        method("getName", "()Ljava/lang/String;") { retRef(self().native) }
    }

    define("java/util/concurrent/CopyOnWriteArrayList") {
        superName = "java/util/ArrayList"
        ctor("()V") { self().native = JList(ArrayList()) }
        ctor("(Ljava/util/Collection;)V") { self().native = JList(vm.elementsOf(r(1)).toMutableList()) }
        method("addIfAbsent", "(Ljava/lang/Object;)Z") {
            val l = (self().native as JList).items
            if (l.any { vm.vmEquals(r(1), it) }) ret(false) else { l.add(r(1)); ret(true) }
        }
    }
    define("java/util/concurrent/CopyOnWriteArraySet") {
        superName = "java/util/LinkedHashSet"
        ctor("()V") { self().native = JHashMap(vm, linked = true) }
    }
    for (q in listOf("java/util/concurrent/ConcurrentLinkedQueue", "java/util/concurrent/ConcurrentLinkedDeque",
        "java/util/concurrent/LinkedBlockingQueue", "java/util/concurrent/LinkedBlockingDeque", "java/util/concurrent/ArrayBlockingQueue")) {
        define(q) {
            superName = "java/util/ArrayDeque"
            ctor("()V") { self().native = JList(ArrayDeque()) }
            ctor("(I)V") { self().native = JList(ArrayDeque()) }
        }
    }
    define("java/util/concurrent/BlockingQueue") { asInterface(); implements("java/util/Queue") }
    define("java/util/concurrent/ConcurrentSkipListMap") { superName = "java/util/TreeMap" }
    define("java/util/concurrent/ConcurrentSkipListSet") { superName = "java/util/TreeSet" }

    // A latch only one thread uses: awaiting one that is not open would wait forever.
    define("java/util/concurrent/CountDownLatch") {
        field("count", "J")
        ctor("(I)V") { self().prims[0] = i(1).toLong() }
        method("countDown", "()V") { val s = self(); if (s.prims[0] > 0) s.prims[0]-- }
        method("getCount", "()J") { ret(self().prims[0]) }
        method("await", "()V") {
            vm.runPendingTasks()
            if (self().prims[0] > 0) vm.throwVm("java/lang/IllegalStateException", "CountDownLatch.await would block forever on a single-threaded VM")
        }
        method("await", "(JLjava/util/concurrent/TimeUnit;)Z") { vm.runPendingTasks(); ret(self().prims[0] == 0L) }
    }
}
