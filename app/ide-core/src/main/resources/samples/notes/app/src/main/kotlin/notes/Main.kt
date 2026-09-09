package notes

/**
 * An interactive command loop over the [Notebook]. At the `>` prompt, type one of:
 *
 *   add <text>     add a note
 *   list           show every note
 *   done <id>      mark a note complete
 *   rm <id>        remove a note
 *   find <text>    search note text
 *   quit           exit (end-of-input / Ctrl-D also quits)
 *
 * This is the app's "view": it reads commands and prints, while [Notebook] holds the data and logic.
 */
fun main() {
    val notebook = Notebook()
    println("Notes — commands: add <text>, list, done <id>, rm <id>, find <text>, quit")

    while (true) {
        print("> ")
        System.out.flush() // show the prompt before we block on input
        val line = readLine()?.trim() ?: break // end of input

        if (line.isEmpty()) continue
        val space = line.indexOf(' ')
        val command = if (space < 0) line else line.substring(0, space)
        val argument = if (space < 0) "" else line.substring(space + 1).trim()

        when (command) {
            "add" -> {
                if (argument.isEmpty()) { println("Usage: add <text>"); continue }
                val note = notebook.add(argument)
                println("Added #${note.id}: ${note.text}")
            }
            "list" -> {
                if (notebook.all().isEmpty()) println("(no notes yet)")
                for (note in notebook.all()) {
                    val mark = if (note.done) "[x]" else "[ ]"
                    println("  $mark ${note.id}. ${note.text}")
                }
            }
            "done" -> {
                val id = argument.toIntOrNull()
                println(if (id != null && notebook.complete(id)) "Completed #$id" else "No note #$argument")
            }
            "rm" -> {
                val id = argument.toIntOrNull()
                println(if (id != null && notebook.remove(id)) "Removed #$id" else "No note #$argument")
            }
            "find" -> {
                val hits = notebook.search(argument)
                if (hits.isEmpty()) println("(no matches)")
                for (note in hits) println("  - ${note.text}")
            }
            "quit", "exit" -> break
            else -> println("Unknown command: $command (try: add, list, done, rm, find, quit)")
        }
    }

    println("Bye!")
}
