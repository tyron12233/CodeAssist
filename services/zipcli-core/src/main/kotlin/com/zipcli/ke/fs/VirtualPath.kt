package com.zipcli.ke.fs

object VirtualPath {
    fun normalize(path: String): String {
        if (path.isEmpty()) return "/"
        val abs = if (path.startsWith("/")) path else "/$path"
        val stack = ArrayDeque<String>()
        for (p in abs.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(p)
            }
        }
        return "/" + stack.joinToString("/")
    }

    fun resolve(cwd: String, path: String): String =
        if (path.startsWith("/")) normalize(path) else normalize("$cwd/$path")

    fun name(path: String): String {
        val n = normalize(path)
        return if (n == "/") "/" else n.substringAfterLast('/')
    }
}