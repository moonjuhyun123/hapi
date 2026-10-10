package app.hapi.companion.feature.chat.jarvis

import app.hapi.companion.feature.chat.PermissionAction
import app.hapi.protocol.chat.AgentReasoningBlock
import app.hapi.protocol.chat.AgentTextBlock
import app.hapi.protocol.chat.ChatBlock
import app.hapi.protocol.chat.ChatToolCall
import app.hapi.protocol.chat.ToolCallBlock
import app.hapi.protocol.chat.ToolGroupBlock
import app.hapi.protocol.chat.ToolGroupingOptions
import app.hapi.protocol.chat.ToolPermission
import app.hapi.protocol.chat.ToolState
import app.hapi.protocol.chat.UserTextBlock
import app.hapi.protocol.chat.VisibleChatBlock
import app.hapi.protocol.chat.buildVisibleChatBlocks
import app.hapi.protocol.wire.HapiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarvisChatModelTest {
    private fun user(id: String, at: Long) = UserTextBlock(id, null, at, null, "hi", null, null, null, null)
    private fun text(id: String, at: Long) = AgentTextBlock(id = id, localId = null, createdAt = at, invokedAt = null, text = "ok", meta = null)
    private fun reasoning(id: String, at: Long) =
        AgentReasoningBlock(id = id, localId = null, createdAt = at, invokedAt = null, text = "hmm", meta = null)
    private fun tool(
        id: String,
        at: Long,
        name: String = "Bash",
        state: String = ToolState.COMPLETED,
        input: String = """{"command":"bun test"}""",
        permission: String? = null,
        children: List<ChatBlock> = emptyList(),
    ) = ToolCallBlock(
        id = id, localId = null, createdAt = at, invokedAt = null,
        tool = ChatToolCall(
            id = id, name = name, state = state, input = HapiJson.parseToJsonElement(input), createdAt = at, description = null,
            permission = permission?.let { ToolPermission(id = "req-$id", status = it, presence = setOf("id", "status")) },
        ),
        children = children, meta = null,
    )

    // ------------------------------------------------------------ activity --

    @Test fun idleWithoutTurnShowsNothing() {
        assertNull(deriveActivity(emptyList(), thinking = false))
        assertNull(deriveActivity(listOf(text("a", 1), user("u", 2)), thinking = false))
    }

    @Test fun thinkingRightAfterUserMessage() {
        val snapshot = deriveActivity(listOf(user("u", 1_000)), thinking = true)!!
        assertEquals(ActivityPhase.Thinking, snapshot.phase)
        assertEquals(0, snapshot.toolCalls)
        assertEquals(1_000L, snapshot.turnStartedAt)
    }

    @Test fun reasoningIsThinking() {
        assertEquals(ActivityPhase.Thinking, deriveActivity(listOf(user("u", 1), reasoning("r", 2)), true)!!.phase)
    }

    @Test fun runningToolIsReportedWithStepCount() {
        val blocks = listOf(user("u", 1), tool("t1", 2), text("a", 3), tool("t2", 4, state = ToolState.RUNNING))
        val snapshot = deriveActivity(blocks, thinking = true)!!
        assertEquals(ActivityPhase.UsingTool, snapshot.phase)
        assertEquals("t2", snapshot.toolId)
        assertEquals(2, snapshot.toolCalls)
    }

    @Test fun completedToolWhileThinkingIsThinking() {
        val snapshot = deriveActivity(listOf(user("u", 1), tool("t1", 2)), thinking = true)!!
        assertEquals(ActivityPhase.Thinking, snapshot.phase)
        assertEquals(1, snapshot.toolCalls)
    }

    @Test fun textAtTheTailIsWriting() {
        assertEquals(ActivityPhase.Writing, deriveActivity(listOf(user("u", 1), tool("t", 2), text("a", 3)), true)!!.phase)
    }

    @Test fun finishedTurnIsDoneWithDuration() {
        val snapshot = deriveActivity(listOf(user("u", 1_000), tool("t", 2_000), text("a", 73_000)), thinking = false)!!
        assertEquals(ActivityPhase.Done, snapshot.phase)
        assertEquals(1, snapshot.toolCalls)
        assertEquals(72L, elapsedSeconds(snapshot.turnStartedAt, snapshot.lastActivityAt))
    }

    @Test fun onlyTheLatestTurnCounts() {
        val blocks = listOf(user("u1", 1), tool("old", 2), text("a", 3), user("u2", 4), text("b", 5))
        assertEquals(0, deriveActivity(blocks, thinking = false)!!.toolCalls)
    }

    @Test fun pendingApprovalWinsEvenWhenNotThinking() {
        val blocks = listOf(user("u", 1), tool("t", 2, state = ToolState.PENDING, permission = "pending"))
        val snapshot = deriveActivity(blocks, thinking = false)!!
        assertEquals(ActivityPhase.AwaitingApproval, snapshot.phase)
        assertEquals("t", snapshot.toolId)
    }

    @Test fun pendingApprovalInsideSubagentIsFound() {
        val child = tool("child", 3, name = "Edit", input = """{"file_path":"a.kt"}""", permission = "pending")
        val parent = tool("task", 2, name = "Task", state = ToolState.RUNNING, input = """{"prompt":"x"}""", children = listOf(child))
        val snapshot = deriveActivity(listOf(user("u", 1), parent), thinking = true)!!
        assertEquals(ActivityPhase.AwaitingApproval, snapshot.phase)
        assertEquals("child", snapshot.toolId)
    }

    @Test fun toolGroupsCountEveryTool() {
        val tools = listOf(
            tool("r1", 2, name = "Read", input = """{"file_path":"a.kt"}"""),
            tool("r2", 3, name = "Read", input = """{"file_path":"b.kt"}""", state = ToolState.RUNNING),
        )
        val grouped: List<VisibleChatBlock> = buildVisibleChatBlocks(tools, ToolGroupingOptions(hasMoreMessages = false))
        assertTrue(grouped.single() is ToolGroupBlock)
        val snapshot = deriveActivity(listOf(user("u", 1)) + grouped, thinking = true)!!
        assertEquals(ActivityPhase.UsingTool, snapshot.phase)
        assertEquals("r2", snapshot.toolId)
        assertEquals(2, snapshot.toolCalls)
        assertEquals(3L, snapshot.lastActivityAt)
    }

    @Test fun transcriptProjectionHidesWhatTheStripNeeds() {
        // ChatViewModel must feed deriveActivity the UNPROJECTED blocks: the
        // transcript projection strips group members and sub-agent children.
        val tools = listOf(
            tool("r1", 2, name = "Read", input = """{"file_path":"a.kt"}"""),
            tool("r2", 3, name = "Read", input = """{"file_path":"b.kt"}""", state = ToolState.RUNNING),
        )
        val child = tool("child", 5, name = "Edit", input = """{"file_path":"c.kt"}""", permission = "pending")
        val task = tool("task", 4, name = "Task", state = ToolState.RUNNING, input = """{"prompt":"x"}""", children = listOf(child))
        val grouped = listOf<VisibleChatBlock>(user("u", 1)) +
            buildVisibleChatBlocks(tools, ToolGroupingOptions(hasMoreMessages = false))
        val projection = app.hapi.companion.feature.chat.TranscriptProjection()

        val projectedGroup = projection.project(grouped)
        assertTrue((projectedGroup.last() as ToolGroupBlock).tools.isEmpty())
        assertEquals(ActivityPhase.Thinking, deriveActivity(projectedGroup, thinking = true)!!.phase)
        assertEquals(ActivityPhase.UsingTool, deriveActivity(grouped, thinking = true)!!.phase)

        val withTask = listOf(user("u", 1), task)
        assertEquals(ActivityPhase.UsingTool, deriveActivity(projection.project(withTask), thinking = true)!!.phase)
        assertEquals(ActivityPhase.AwaitingApproval, deriveActivity(withTask, thinking = true)!!.phase)
    }

    @Test fun snapshotCapturesTheToolCallImmutably() {
        val running = tool("t", 2, state = ToolState.RUNNING)
        val snapshot = deriveActivity(listOf(user("u", 1), running), thinking = true)!!
        val captured = snapshot.toolCall
        running.tool = running.tool.copy(state = ToolState.COMPLETED)
        assertEquals(ToolState.RUNNING, captured?.state)
        assertEquals("t", snapshot.toolId)
    }

    @Test fun stripNeverNamesTheHarness() {
        // Semantic titles pass through untouched, even if they mention a brand.
        assertEquals("Apply changes", neutralToolTitle("Apply changes", "CodexPatch"))
        assertEquals("codex exec ls", neutralToolTitle("codex exec ls", "Bash"))
        // Raw fallback titles lose the leading brand.
        assertEquals("Foo", neutralToolTitle("CodexFoo", "CodexFoo"))
        assertEquals("web_lookup", neutralToolTitle("Claude_web_lookup", "Claude_web_lookup"))
        assertEquals("", neutralToolTitle("Codex", "Codex"))
        assertEquals("Codecheck", neutralToolTitle("Codecheck", "Codecheck"))
    }

    @Test fun elapsedNeverNegative() {
        assertEquals(0L, elapsedSeconds(5_000, 1_000))
        assertNull(elapsedSeconds(null, 1_000))
    }

    // ---------------------------------------------------------- permissions --

    @Test fun permissionKinds() {
        assertEquals(PermissionKind.Command, permissionKind(tool("a", 1).tool))
        assertEquals(PermissionKind.FileEdit, permissionKind(tool("b", 1, name = "Edit", input = "{}").tool))
        assertEquals(PermissionKind.Web, permissionKind(tool("c", 1, name = "WebFetch", input = "{}").tool))
        assertEquals(PermissionKind.Command, permissionKind(tool("d", 1, name = "SomeShell", input = """{"cmd":"ls"}""").tool))
        assertEquals(PermissionKind.Other, permissionKind(tool("e", 1, name = "mcp__x__y", input = "{}").tool))
    }

    @Test fun rawTextIsCommandOrPrettyJson() {
        assertEquals("bun test", permissionRawText(tool("a", 1).tool))
        val raw = permissionRawText(tool("b", 1, name = "Edit", input = """{"file_path":"a.kt","old_string":"x"}""").tool)
        assertTrue(raw.contains("\"file_path\": \"a.kt\""), raw)
        assertTrue(raw.contains('\n'))
        val long = permissionRawText(tool("c", 1, input = """{"command":"${"x".repeat(RAW_TEXT_LIMIT + 50)}"}""").tool)
        assertTrue(long.endsWith("…"))
        assertTrue(long.length <= RAW_TEXT_LIMIT + 2)
    }

    // ------------------------------------------------------------ questions --

    @Test fun singleChoiceAskUserQuestionBecomesButtons() {
        val input = """{"questions":[{"header":"오늘","question":"운동 가세요?","multiSelect":false,
            "options":[{"label":"운동 간다"},{"label":"못 가","description":"내일로"}]}]}"""
        val quick = quickQuestion(tool("q", 1, name = "AskUserQuestion", input = input).tool)!!
        assertEquals("오늘", quick.header)
        assertEquals("운동 가세요?", quick.prompt)
        assertEquals(listOf("운동 간다", "못 가"), quick.choices.map { it.label })
        assertEquals("내일로", quick.choices[1].description)
        assertEquals(PermissionAction.FlatAnswers(mapOf("0" to listOf("못 가"))), quick.choices[1].action)
    }

    @Test fun cursorQuestionUsesStableIds() {
        val input = """{"questions":[{"id":"gym","prompt":"Go?","options":[{"id":"yes","label":"Yes"},{"label":"No"}]}]}"""
        val quick = quickQuestion(tool("q", 1, name = "CursorAskQuestion", input = input).tool)!!
        assertEquals(PermissionAction.FlatAnswers(mapOf("gym" to listOf("yes"))), quick.choices[0].action)
        assertEquals(PermissionAction.FlatAnswers(mapOf("gym" to listOf("No"))), quick.choices[1].action)
    }

    @Test fun formsStayForMultiSelectOrSeveralQuestions() {
        val multi = """{"questions":[{"question":"Pick","multiSelect":true,"options":[{"label":"A"},{"label":"B"}]}]}"""
        assertNull(quickQuestion(tool("q1", 1, name = "AskUserQuestion", input = multi).tool))
        val two = """{"questions":[{"question":"1","options":[{"label":"A"}]},{"question":"2","options":[{"label":"B"}]}]}"""
        assertNull(quickQuestion(tool("q2", 1, name = "AskUserQuestion", input = two).tool))
        val freeText = """{"questions":[{"question":"Why?","options":[]}]}"""
        assertNull(quickQuestion(tool("q3", 1, name = "AskUserQuestion", input = freeText).tool))
        assertNull(quickQuestion(tool("q4", 1).tool))
    }

    @Test fun requestUserInputSingleChoiceKeepsFormPayload() {
        val input = """{"questions":[{"id":"target","question":"Deploy where?","isOther":true,"prefill":"note",
            "options":[{"label":"staging"},{"label":"production"}]}]}"""
        val quick = quickQuestion(tool("q", 1, name = "request_user_input", input = input).tool)!!
        // "None of the above" stays in the typed form, not a one-tap button.
        assertEquals(listOf("staging", "production"), quick.choices.map { it.label })
        assertEquals(
            PermissionAction.NestedAnswers(mapOf("target" to listOf("staging", "user_note: note"))),
            quick.choices[0].action,
        )
        val multiple = """{"questions":[{"id":"t","question":"?","multiple":true,"options":[{"label":"a"}]}]}"""
        assertNull(quickQuestion(tool("q2", 1, name = "request_user_input", input = multiple).tool))
    }
}
