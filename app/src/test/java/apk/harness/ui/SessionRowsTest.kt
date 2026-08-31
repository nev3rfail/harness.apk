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

    private fun project(path: String?, vararg chats: String) = Project(
        path = path,
        reachable = true,
        chats = chats.map(::chat),
    )

    /**
     * A chat row's id, spelled the way the host spells a path, so the
     * expectation is about the scheme rather than about the separator.
     */
    private fun chatId(id: String) = "c:" + File("/transcripts/$id.jsonl").path

    /** [n] chat names, so a project past the cap fits in one expression. */
    private fun names(n: Int) = Array(n) { "chat-%02d".format(it) }

    @Test
    fun `a collapsed project is one row with no children`() {
        val rows = sessionRows(listOf(project("/one", "a", "b")), emptySet(), emptySet())

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
        val rows = sessionRows(listOf(project("/one", "a", "b", "c")), setOf("/one"), emptySet())

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
            emptySet(),
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
            emptySet(),
        )

        assertEquals(listOf("p:/one", chatId("a"), "p:/two"), rows.map { it.id })
        assertEquals(listOf(true), rows[1].ancestorsContinue)
        assertFalse((rows[2] as SessionRow.ProjectRow).hasChildren)
    }

    @Test
    fun `an expanded project with no chats has no children`() {
        val rows = sessionRows(listOf(project("/empty")), setOf("/empty"), emptySet())

        assertEquals(1, rows.size)
        assertFalse(rows[0].hasChildren)
    }

    @Test
    fun `the project naming no directory is keyed by the empty string`() {
        val rows = sessionRows(listOf(project(null, "a")), setOf(""), emptySet())

        assertEquals(listOf("p:", chatId("a")), rows.map { it.id })
        assertTrue(rows[0].hasChildren)
    }

    @Test
    fun `no projects yield no rows`() {
        assertTrue(sessionRows(emptyList(), setOf("/one"), emptySet()).isEmpty())
    }

    @Test
    fun `an open project past the cap shows six chats and a row for the rest`() {
        val rows = sessionRows(listOf(project("/one", *names(10))), setOf("/one"), emptySet())

        assertEquals(8, rows.size)
        val chats = rows.drop(1).dropLast(1).map { it as SessionRow.ChatRow }
        assertEquals(
            listOf("chat-00", "chat-01", "chat-02", "chat-03", "chat-04", "chat-05"),
            chats.map { it.chat.sessionId },
        )

        val more = rows.last() as SessionRow.MoreRow
        assertEquals(4, more.hidden)
        assertEquals(1, more.depth)
        assertEquals(listOf(false), more.ancestorsContinue)
        assertFalse(more.hasChildren)
    }

    @Test
    fun `the last chat drawn is not the last sibling, and the reveal row is`() {
        val rows = sessionRows(listOf(project("/one", *names(10))), setOf("/one"), emptySet())

        val sixth = rows[6] as SessionRow.ChatRow
        assertEquals("chat-05", sixth.chat.sessionId)
        // The guide carries on past it to reach the row that closes the
        // project's column.
        assertFalse(sixth.isLastSibling)
        assertTrue(rows.last().isLastSibling)
    }

    @Test
    fun `a project of exactly six has nothing behind it`() {
        val rows = sessionRows(listOf(project("/one", *names(6))), setOf("/one"), emptySet())

        assertEquals(7, rows.size)
        assertTrue(rows.none { it is SessionRow.MoreRow })
        assertTrue(rows.last().isLastSibling)
    }

    @Test
    fun `a revealed project draws every chat and no reveal row`() {
        val rows = sessionRows(listOf(project("/one", *names(10))), setOf("/one"), setOf("/one"))

        assertEquals(11, rows.size)
        assertTrue(rows.none { it is SessionRow.MoreRow })
        assertEquals("chat-09", (rows.last() as SessionRow.ChatRow).chat.sessionId)
        assertTrue(rows.last().isLastSibling)
    }

    @Test
    fun `a revealed project that is collapsed draws nothing`() {
        val rows = sessionRows(listOf(project("/one", *names(10))), emptySet(), setOf("/one"))

        assertEquals(1, rows.size)
        assertFalse(rows[0].hasChildren)
    }

    @Test
    fun `an open project has children whether or not it is capped`() {
        val capped = sessionRows(listOf(project("/one", *names(10))), setOf("/one"), emptySet())
        val opened = sessionRows(listOf(project("/one", *names(10))), setOf("/one"), setOf("/one"))
        val small = sessionRows(listOf(project("/one", *names(3))), setOf("/one"), emptySet())

        assertTrue(capped[0].hasChildren)
        assertTrue(opened[0].hasChildren)
        assertTrue(small[0].hasChildren)
    }

    @Test
    fun `the reveal row's key is its own`() {
        val rows = sessionRows(listOf(project("/one", *names(10))), setOf("/one"), emptySet())

        assertEquals("m:/one", rows.last().id)
        // The list is keyed by id, so a reveal row cannot collide with a chat's.
        assertEquals(rows.size, rows.mapTo(mutableSetOf()) { it.id }.size)
    }

    @Test
    fun `a reveal row continues its ancestor while a project follows`() {
        val rows = sessionRows(
            listOf(project("/one", *names(10)), project("/two", "b")),
            setOf("/one"),
            emptySet(),
        )

        val more = rows[7] as SessionRow.MoreRow
        assertEquals("m:/one", more.id)
        assertEquals(listOf(true), more.ancestorsContinue)
    }
}
