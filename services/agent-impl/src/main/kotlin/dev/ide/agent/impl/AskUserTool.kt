package dev.ide.agent.impl

import dev.ide.agent.AgentTool
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec

/** One multiple-choice question the agent asks the user. */
data class AgentQuestion(
    val question: String,
    /** A very short label for the question (a chip in the UI), e.g. "Language". */
    val header: String,
    val options: List<AgentQuestionOption>,
    val multiSelect: Boolean,
)

data class AgentQuestionOption(val label: String, val description: String = "")

/**
 * Shows [AgentQuestion]s to the user and suspends until they answer. Returns one list per question, in order: the
 * labels chosen, plus any text they typed for "Other". Null when the user dismissed the questions.
 */
fun interface AgentQuestionAsker {
    suspend fun ask(questions: List<AgentQuestion>): List<List<String>>?
}

/**
 * `ask_user`: multiple-choice questions the host draws as tappable choices, the way Claude Code asks. The loop
 * waits for the answer like it waits for a write permission, and the answer comes back as the tool result.
 * "Other" with free text is always offered by the host, so the options do not need a catch-all.
 */
fun askUserTool(asker: AgentQuestionAsker): AgentTool = object : AgentTool {
    override val spec = ToolSpec(
        name = ASK_USER_TOOL,
        description = "Ask the user 1-4 multiple-choice questions and wait for the answers; the app shows them as " +
            "buttons. Use it whenever you would otherwise list options for the user to pick from, end your reply " +
            "with a question, or need a decision only the user can make (which approach, which design, which " +
            "library). Put each option's one-line explanation in its description rather than in your reply. Do " +
            "not ask for anything you can find out with your other tools, and do not ask to confirm a plan you " +
            "can simply carry out. Give 2-4 distinct options per question, most " +
            "recommended first (add \"(Recommended)\" to its label); the user can always type their own answer " +
            "instead, so do not add an \"Other\" option.",
        parameters = ASK_SCHEMA,
    )

    override fun summarize(args: ToolArgs): String =
        parse(args)?.firstOrNull()?.let { "ask: ${it.question}" } ?: "ask the user"

    override suspend fun execute(args: ToolArgs): ToolExecutionResult {
        val questions = parse(args)
            ?: return ToolExecutionResult.error(
                "questions must be 1-4 objects of {question, header, options: 2-4 {label, description}, multi_select}.",
            )
        val answers = asker.ask(questions)
            ?: return ToolExecutionResult.ok(
                "The user dismissed the questions without answering. Do not ask them again; continue with " +
                    "sensible defaults and say which ones you chose.",
            )
        val lines = questions.mapIndexed { i, q ->
            val picked = answers.getOrNull(i).orEmpty().filter { it.isNotBlank() }
            "- ${q.question} -> ${if (picked.isEmpty()) "(no answer)" else picked.joinToString(", ")}"
        }
        return ToolExecutionResult.ok("The user answered:\n" + lines.joinToString("\n") + "\nContinue using these answers.")
    }
}

const val ASK_USER_TOOL = "ask_user"

private val ASK_SCHEMA = """
    {"type":"object","properties":{"questions":{"type":"array","description":"1-4 questions, asked together.",
    "items":{"type":"object","properties":{
    "question":{"type":"string","description":"The full question, ending with a question mark."},
    "header":{"type":"string","description":"A label of at most 12 characters, e.g. \"Database\"."},
    "options":{"type":"array","description":"2-4 distinct choices.","items":{"type":"object","properties":{
    "label":{"type":"string","description":"The choice, 1-5 words."},
    "description":{"type":"string","description":"What choosing it means, one sentence."}},
    "required":["label","description"],"additionalProperties":false}},
    "multi_select":{"type":"boolean","description":"Whether several options may be chosen."}},
    "required":["question","header","options","multi_select"],"additionalProperties":false}}},
    "required":["questions"],"additionalProperties":false}
""".trimIndent().replace("\n", "")

/** The questions in [args], or null when they are not 1-4 questions with 2-4 options each. */
private fun parse(args: ToolArgs): List<AgentQuestion>? {
    val items = runCatching { AgentJson.parseToJsonElement(args.raw()) }.getOrNull()
        .asObj()?.get("questions").asArr() ?: return null
    val questions = items.mapNotNull { it.asObj() }.mapNotNull { o ->
        val question = o["question"].asStr()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val options = o["options"].asArr()?.mapNotNull { it.asObj() }?.mapNotNull { opt ->
            val label = opt["label"].asStr()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AgentQuestionOption(label, opt["description"].asStr()?.trim().orEmpty())
        }.orEmpty().distinctBy { it.label }
        if (options.size !in 2..4) return@mapNotNull null
        AgentQuestion(
            question = question,
            header = o["header"].asStr()?.trim().orEmpty().take(MAX_HEADER),
            options = options,
            multiSelect = o["multi_select"]?.toString() == "true",
        )
    }
    return questions.takeIf { it.size in 1..4 && it.size == items.size }
}

private const val MAX_HEADER = 16
