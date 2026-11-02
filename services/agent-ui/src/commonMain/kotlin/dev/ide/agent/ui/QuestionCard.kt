package dev.ide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_question_next
import dev.ide.agent.ui.generated.resources.chat_question_next_page
import dev.ide.agent.ui.generated.resources.chat_question_other
import dev.ide.agent.ui.generated.resources.chat_question_page
import dev.ide.agent.ui.generated.resources.chat_question_previous
import dev.ide.agent.ui.generated.resources.chat_question_other_hint
import dev.ide.agent.ui.generated.resources.chat_question_skip
import dev.ide.agent.ui.generated.resources.chat_question_submit
import dev.ide.ui.backend.UiAgentQuestion
import dev.ide.ui.backend.UiAgentQuestionRequest
import dev.ide.ui.components.Chip
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The agent's `ask_user` questions, over the composer while the turn waits. Each question lists its options (a
 * radio for one answer, checks for several) and an "Other" row that opens a text field. Several questions are
 * paged, one at a time, with arrows and Next; picking a one-answer question moves on by itself. A single
 * one-answer question is sent the moment an option is tapped; anything else ends with Submit. Skip dismisses them all,
 * and the agent carries on with defaults.
 */
@Composable
internal fun QuestionCard(request: UiAgentQuestionRequest, onAnswer: (List<List<String>>?) -> Unit) {
    val questions = request.questions
    // Per question: the labels picked, whether "Other" is on, and what was typed for it. Keyed on the request, so
    // a new set of questions starts empty.
    val picked = remember(request.id) { mutableStateListOf(*Array(questions.size) { emptySet<String>() }) }
    val otherOn = remember(request.id) { mutableStateListOf(*Array(questions.size) { false }) }
    val otherText = remember(request.id) { mutableStateListOf(*Array(questions.size) { "" }) }
    val instant = questions.size == 1 && !questions[0].multiSelect
    // One question per page, like Claude's own question dialog; the arrows and Next move between them.
    var page by remember(request.id) { mutableIntStateOf(0) }
    val last = questions.lastIndex

    fun answers(): List<List<String>> = questions.indices.map { i ->
        picked[i].toList() + listOfNotNull(otherText[i].trim().takeIf { otherOn[i] && it.isNotEmpty() })
    }
    fun answered(i: Int) = picked[i].isNotEmpty() || (otherOn[i] && otherText[i].isNotBlank())
    val complete = questions.indices.all(::answered)

    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.lg)
    Column(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .clip(shape)
            .background(scheme.surfaceContainerHigh, shape)
            .border(1.dp, scheme.primary.copy(alpha = 0.5f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (questions.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.chat_question_page, page + 1, questions.size),
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButtonCa(
                    CaIcons.chevronLeft, stringResource(Res.string.chat_question_previous),
                    { if (page > 0) page-- }, iconSize = 18, boxSize = 34,
                    tint = if (page > 0) scheme.onSurface else scheme.outlineVariant,
                )
                IconButtonCa(
                    CaIcons.chevronRight, stringResource(Res.string.chat_question_next_page),
                    { if (page < last) page++ }, iconSize = 18, boxSize = 34,
                    tint = if (page < last) scheme.onSurface else scheme.outlineVariant,
                )
            }
        }
        val i = page
        val q = questions[i]
        // Keyed on the page, so each question opens scrolled to its top.
        androidx.compose.runtime.key(i) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                QuestionBlock(
                    question = q,
                    picked = picked[i],
                    otherOn = otherOn[i],
                    otherText = otherText[i],
                    onPick = { label ->
                        if (q.multiSelect) {
                            picked[i] = if (label in picked[i]) picked[i] - label else picked[i] + label
                        } else {
                            picked[i] = setOf(label)
                            otherOn[i] = false
                            when {
                                instant -> onAnswer(listOf(listOf(label)))
                                // A one-answer question is done once picked: straight on to the next.
                                i < last -> page = i + 1
                            }
                        }
                    },
                    onOther = {
                        otherOn[i] = !otherOn[i]
                        if (!q.multiSelect && otherOn[i]) picked[i] = emptySet()
                    },
                    onOtherText = { otherText[i] = it },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onAnswer(null) }) { Text(stringResource(Res.string.chat_question_skip)) }
            Box(Modifier.weight(1f))
            when {
                // A lone one-answer question is sent by its tap; Submit only appears once "Other" needs sending.
                instant && !otherOn[0] -> Unit
                i < last -> PrimaryButton(
                    stringResource(Res.string.chat_question_next),
                    onClick = { if (answered(i)) page = i + 1 },
                )
                else -> PrimaryButton(
                    stringResource(Res.string.chat_question_submit),
                    onClick = {
                        if (complete) onAnswer(answers())
                        // Something earlier is still unanswered: go to it rather than doing nothing.
                        else page = questions.indices.first { !answered(it) }
                    },
                )
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    question: UiAgentQuestion,
    picked: Set<String>,
    otherOn: Boolean,
    otherText: String,
    onPick: (String) -> Unit,
    onOther: () -> Unit,
    onOtherText: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (question.header.isNotBlank()) {
            Chip(question.header, fill = scheme.secondaryContainer, textColor = scheme.onSecondaryContainer)
        }
        Text(question.question, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        question.options.forEach { option ->
            ChoiceRow(
                label = option.label,
                description = option.description,
                selected = option.label in picked,
                multi = question.multiSelect,
                onClick = { onPick(option.label) },
            )
        }
        ChoiceRow(
            label = stringResource(Res.string.chat_question_other),
            description = "",
            selected = otherOn,
            multi = question.multiSelect,
            onClick = onOther,
        )
        if (otherOn) {
            val fieldShape = RoundedCornerShape(Ca.radius.md)
            Box(
                Modifier.fillMaxWidth().padding(start = 30.dp)
                    .clip(fieldShape)
                    .background(scheme.surfaceContainerHighest, fieldShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (otherText.isEmpty()) {
                    Text(stringResource(Res.string.chat_question_other_hint), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                BasicTextField(
                    value = otherText,
                    onValueChange = onOtherText,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, description: String, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.md)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainer, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val on = if (selected) scheme.onSecondaryContainer else scheme.onSurface
        // A check box for several answers, a radio dot for one.
        val markShape = if (multi) RoundedCornerShape(4.dp) else CircleShape
        Box(
            Modifier.size(20.dp).clip(markShape)
                .background(if (selected) scheme.primary else scheme.surfaceContainer, markShape)
                .border(2.dp, if (selected) scheme.primary else scheme.outline, markShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(CaIcons.check, null, Modifier.size(14.dp), tint = scheme.onPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = on, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (description.isNotBlank()) {
                Text(description, color = on.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
