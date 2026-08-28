package apk.harness.ui

import apk.harness.chats.Chat
import apk.harness.chats.Project
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRowsTest {

    private fun chat(id: String) = Chat(
        sessionId = id,
        title = id,
        lastPrompt = null,
        modified = 0L,
        transcript = File("/transcripts/$id.jsonl"),
    )

    private fun project(path: String, vararg chats: String) = Project(
        path = path,
        reachable = true,
        guessed = false,
        chats = chats.map(::chat),
    )

    /**
     * A chat row's id, spelled the way the host spells a path, so the
     * expectation is about the scheme rather than about the separator.
     */
    private fun chatId(id: String) = "c:" + File("/transcripts/$id.jsonl").path

    @Test
    fun `a collapsed project is one row with no children`() {
        val rows = sessionRows(listOf(project("/one", "a", "b")), emptySet())

        assertEquals(1, rows.size)
        val row = rows[0] as SessionRow.ProjectRow
        assertEquals("p:/one", row.id)
        assertEquals(0, row.depth)
        assertEquals(emptyList<Boolean>(), row.ancestorsContinue)
        assertTrue(row.isLastSibling)
        assertFalse(row.hasChildren)
    }

    @Test
    fun `an expanded project carries its chats`() {
        val rows = sessionRows(listOf(project("/one", "a", "b", "c")), setOf("/one"))

        assertEquals(4, rows.size)
        assertEquals(
            listOf("p:/one", chatId("a"), chatId("b"), chatId("c")),
            rows.map { it.id },
        )

        val project = rows[0] as SessionRow.ProjectRow
        assertTrue(project.hasChildren)
        assertTrue(project.isLastSibling)

        val chats = rows.drop(1).map { it as SessionRow.ChatRow }
        assertEquals(listOf(1, 1, 1), chats.map { it.depth })
        // The only project, so nothing above a chat continues past it.
        assertEquals(listOf(false), chats[0].ancestorsContinue)
        assertFalse(chats[0].hasChildren)
        assertFalse(chats[0].isLastSibling)
        assertFalse(chats[1].isLastSibling)
        assertTrue(chats[2].isLastSibling)
    }

    @Test
    fun `a chat's ancestor continues only while a project follows`() {
        val rows = sessionRows(
            listOf(project("/one", "a"), project("/two", "b")),
            setOf("/one", "/two"),
        )

        assertEquals(listOf("p:/one", chatId("a"), "p:/two", chatId("b")), rows.map { it.id })
        assertFalse((rows[0] as SessionRow.ProjectRow).isLastSibling)
        assertEquals(listOf(true), rows[1].ancestorsContinue)
        assertTrue((rows[2] as SessionRow.ProjectRow).isLastSibling)
        assertEquals(listOf(false), rows[3].ancestorsContinue)
    }

    @Test
    fun `a collapsed project contributes no chats to the row below it`() {
        val rows = sessionRows(
            listOf(project("/one", "a"), project("/two", "b")),
            setOf("/one"),
        )

        assertEquals(listOf("p:/one", chatId("a"), "p:/two"), rows.map { it.id })
        assertEquals(listOf(true), rows[1].ancestorsContinue)
        assertFalse((rows[2] as SessionRow.ProjectRow).hasChildren)
    }

    @Test
    fun `an expanded project with no chats has no children`() {
        val rows = sessionRows(listOf(project("/empty")), setOf("/empty"))

        assertEquals(1, rows.size)
        assertFalse(rows[0].hasChildren)
    }

    @Test
    fun `no projects yield no rows`() {
        assertTrue(sessionRows(emptyList(), setOf("/one")).isEmpty())
    }
}
