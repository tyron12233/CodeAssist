package dev.codeassist.csharp.completion


internal class CsSymbolTable(lines: List<String>) {

    /** A public type. [namespace] is `-` when the type has no namespace. */
    class Type(
        val namespace: String,
        val name: String,
        val kind: String,
        val isStatic: Boolean,
    ) {
        val fullName: String
            get() = if (namespace == "-") name else "$namespace.$name"
    }

    /** A public member of [typeFullName]. [paramCount] is 0 for non-method members. */
    class Member(
        val typeFullName: String,
        val kind: String,
        val name: String,
        val isStatic: Boolean,
        val paramCount: Int = 0,
    )

    val types = ArrayList<Type>()
    val members = ArrayList<Member>()

    // Parse each line directly to avoid intermediate lists.
    init {
        for (line in lines) {
            val fields = line.split('\t')

            when (fields[0]) {
                "type" -> if (fields.size >= 5) {
                    types.add(
                        Type(
                            namespace = fields[1],
                            name = fields[2],
                            kind = fields[3],
                            isStatic = fields[4] == "1",
                        ),
                    )
                }

                "member" -> if (fields.size >= 6) {
                    val typeFullName =
                        if (fields[1] == "-") fields[2] else "${fields[1]}.${fields[2]}"
                    val paramCount = fields.getOrNull(6)?.toIntOrNull() ?: 0

                    members.add(
                        Member(
                            typeFullName = typeFullName,
                            kind = fields[3],
                            name = fields[4],
                            isStatic = fields[5] == "1",
                            paramCount = paramCount,
                        ),
                    )
                }
            }
        }
    }
}
