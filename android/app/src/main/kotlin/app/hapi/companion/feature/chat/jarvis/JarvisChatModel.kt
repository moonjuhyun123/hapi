package app.hapi.companion.feature.chat.jarvis

import app.hapi.companion.feature.chat.PermissionAction
import app.hapi.companion.feature.chat.permissions.isCursorAskQuestionToolName
import app.hapi.companion.feature.chat.permissions.parseAskUserQuestions
import app.hapi.companion.feature.chat.permissions.parseRequestUserInputQuestions
import app.hapi.companion.feature.chat.permissions.requestUserInputAnswerValues
import app.hapi.companion.feature.chat.terminalCommand
import app.hapi.protocol.chat.AgentTextBlock
import app.hapi.protocol.chat.ChatBlock
import app.hapi.protocol.chat.ChatToolCall
import app.hapi.protocol.chat.ToolCallBlock
import app.hapi.protocol.chat.ToolGroupBlock
import app.hapi.protocol.chat.ToolState
import app.hapi.protocol.chat.UserTextBlock
import app.hapi.protocol.chat.VisibleChatBlock
import app.hapi.protocol.chat.isAskUserQuestionToolName
import app.hapi.protocol.chat.isRequestUserInputToolName
import app.hapi.protocol.wire.HapiJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * Jarvis fork additions (see docs/jarvis/CHANGES.md). Pure functions only, so
 * they run in JVM unit tests. Everything here reads the hub's common block
 * model; nothing branches on the harness (Claude / Codex) name.
 */

// ------------------------------------------------------------ activity --

/** What the agent is doing right now, in the five user-facing phases. */
enum class ActivityPhase { Thinking, UsingTool, AwaitingApproval, Writing, Done }

data class ActivitySnapshot(
    val phase: ActivityPhase,
    /** Id of the running / approval-waiting tool, when [phase] is about one. */
    val toolId: String? = null,
    /** That tool's call, captured immutably (blocks are mutated by the pipeline). */
    val toolCall: ChatToolCall? = null,
    /** Top-level tool calls since the last user message ("step" count). */
    val toolCalls: Int,
    /** createdAt (ms) of the user message that started this turn. */
    val turnStartedAt: Long?,
    /** createdAt (ms) of the newest block in this turn. */
    val lastActivityAt: Long?,
)

/**
 * Reduce the visible transcript to one status line. The turn is everything
 * after the newest user message. `thinking` is the hub's session flag.
 * Returns null when there is nothing worth showing (idle, no turn yet).
 */
fun deriveActivity(blocks: List<VisibleChatBlock>, thinking: Boolean): ActivitySnapshot? {
    val start = blocks.indexOfLast { it is UserTextBlock }
    val turn = blocks.subList(start + 1, blocks.size)
    val startedAt = (blocks.getOrNull(start) as? UserTextBlock)?.createdAt

    var toolCalls = 0
    var running: ToolCallBlock? = null
    var awaiting: ToolCallBlock? = null
    for (block in turn) {
        for (tool in topLevelTools(block)) {
            toolCalls += 1
            if (tool.tool.state == ToolState.RUNNING || tool.tool.state == ToolState.PENDING) running = tool
            pendingPermission(tool)?.let { awaiting = it }
        }
    }
    val lastAt = turn.lastOrNull()?.let(::latestCreatedAt)
    fun snapshot(phase: ActivityPhase, tool: ToolCallBlock? = null) =
        ActivitySnapshot(phase, tool?.id, tool?.tool, toolCalls, startedAt, lastAt)

    // A pending approval blocks the agent whatever the thinking flag says.
    awaiting?.let { return snapshot(ActivityPhase.AwaitingApproval, it) }
    if (!thinking) return if (turn.isEmpty()) null else snapshot(ActivityPhase.Done)
    return when (turn.lastOrNull()) {
        is AgentTextBlock -> snapshot(ActivityPhase.Writing)
        is ToolCallBlock, is ToolGroupBlock ->
            if (running != null) snapshot(ActivityPhase.UsingTool, running) else snapshot(ActivityPhase.Thinking)
        else -> snapshot(ActivityPhase.Thinking)
    }
}

private fun topLevelTools(block: VisibleChatBlock): List<ToolCallBlock> = when (block) {
    is ToolCallBlock -> listOf(block)
    is ToolGroupBlock -> block.tools
    else -> emptyList()
}

/** The tool itself, or a sub-agent child (one level), waiting for approval. */
private fun pendingPermission(block: ToolCallBlock): ToolCallBlock? {
    if (block.tool.permission?.status == "pending") return block
    return block.children.filterIsInstance<ToolCallBlock>().lastOrNull { it.tool.permission?.status == "pending" }
}

private fun latestCreatedAt(block: VisibleChatBlock): Long = when (block) {
    is ToolGroupBlock -> block.tools.maxOfOrNull { it.createdAt } ?: block.createdAt
    is ChatBlock -> block.createdAt
}

/** "42초" / "1분 12초" input: whole seconds, never negative (clock skew). */
fun elapsedSeconds(fromMs: Long?, toMs: Long?): Long? {
    if (fromMs == null || toMs == null) return null
    return ((toMs - fromMs) / 1000).coerceAtLeast(0)
}

// ---------------------------------------------------------- permissions --

/** Coarse "what does this request want to do" class, for the header line. */
enum class PermissionKind { Command, FileEdit, Web, Other }

private val COMMAND_TOOLS = setOf("Bash", "CodexBash", "shell_command", "run_shell_command", "shell", "exec_command")
private val FILE_EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write", "NotebookEdit", "CodexPatch", "apply_patch")
private val WEB_TOOLS = setOf("WebFetch", "WebSearch")

fun permissionKind(tool: ChatToolCall): PermissionKind = when {
    tool.name in COMMAND_TOOLS -> PermissionKind.Command
    tool.name in FILE_EDIT_TOOLS -> PermissionKind.FileEdit
    tool.name in WEB_TOOLS -> PermissionKind.Web
    terminalCommand(tool.input) != null -> PermissionKind.Command
    else -> PermissionKind.Other
}

private val PrettyJson = Json(from = HapiJson) { prettyPrint = true }

/** Upper bound for the inline raw view; the inspector keeps the full input. */
const val RAW_TEXT_LIMIT = 6000

/**
 * The request's original text for the "show original" view: the command line
 * for shell tools, otherwise the pretty-printed input as sent by the agent.
 */
fun permissionRawText(tool: ChatToolCall): String {
    val text = terminalCommand(tool.input)
        ?: tool.input?.let { PrettyJson.encodeToString(JsonElement.serializer(), it) }
        ?: tool.name
    return if (text.length > RAW_TEXT_LIMIT) text.take(RAW_TEXT_LIMIT) + "\n…" else text
}

// ------------------------------------------------------------ questions --

data class QuickChoice(val label: String, val description: String?, val action: PermissionAction)

data class QuickQuestion(val header: String?, val prompt: String, val choices: List<QuickChoice>)

/**
 * One question with single-choice options → one tap answers it. The answer
 * payload is exactly what the full form would send for that option (same
 * keys, Cursor stable ids, request_user_input prefill note). Anything else
 * (several questions, multi-select, free text only) returns null and keeps
 * the original form.
 */
fun quickQuestion(tool: ChatToolCall): QuickQuestion? {
    if (isAskUserQuestionToolName(tool.name)) {
        val cursor = isCursorAskQuestionToolName(tool.name)
        val question = parseAskUserQuestions(tool.input, cursor).singleOrNull() ?: return null
        if (question.multiSelect || question.options.isEmpty()) return null
        val key = question.answerKey(0, cursor)
        return QuickQuestion(
            header = question.header?.takeIf { it.isNotBlank() },
            prompt = question.question,
            choices = question.options.map { option ->
                val value = if (cursor) option.id?.takeIf { it.isNotBlank() } ?: option.label else option.label
                QuickChoice(option.label, option.description, PermissionAction.FlatAnswers(mapOf(key to listOf(value))))
            },
        )
    }
    if (isRequestUserInputToolName(tool.name)) {
        val question = parseRequestUserInputQuestions(tool.input).singleOrNull() ?: return null
        if (question.multiple || question.options.isEmpty()) return null
        return QuickQuestion(
            header = null,
            prompt = question.question,
            choices = question.options.map { option ->
                val values = requestUserInputAnswerValues(listOf(option.label), question.prefill.orEmpty())
                QuickChoice(option.label, option.description, PermissionAction.NestedAnswers(mapOf(question.id to values)))
            },
        )
    }
    return null
}
