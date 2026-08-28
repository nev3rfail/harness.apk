package apk.harness.agents

import apk.harness.chats.Chat
import apk.harness.chats.Project
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBackendTest {

    /**
     * A backend that answers with the history it was built around.
     *
     * The merge asks a backend for its history and for nothing else, so the
     * rest of the interface is here to satisfy it rather than to be called.
     */
    private class Fake(
        override val id: String,
        private val history: List<Project>,
    ) : AgentBackend {
        override fun projects(home: File): List<Project> = history

        override val script: String get() = unreached()
        override fun running(home: File) = unreached()
        override fun resume(sessionId: String) = unreached()
        override fun start(sessionId: String) = unreached()
        override fun mcpConfig(file: File) = unreached()
        override fun switch(sessionId: String) = unreached()
        override fun mostRecent(home: File, directory: File) = unreached()

        private fun unreached(): Nothing = error("the merge does not reach this")
    }

    private val home = File("/home")

    // A tab names its directory as a File and a project names it as a string, so
    // both come from one File here and the test says the same thing on any host.
    private val work = File("/work")
    private val elsewhere = File("/elsewhere")

    private fun chat(sessionId: String, modified: Long, backendId: String = "claude") = Chat(
        sessionId = sessionId,
        title = sessionId,
        lastPrompt = null,
        modified = modified,
        transcript = File("/transcripts/$sessionId.jsonl"),
        backendId = backendId,
    )

    private fun project(
        path: String,
        chats: List<Chat>,
        reachable: Boolean = true,
        guessed: Boolean = false,
    ) = Project(path = path, reachable = reachable, guessed = guessed, chats = chats)

    @Test
    fun `one directory with two histories is one project holding both`() {
        val merged = mergedProjects(
            listOf(
                Fake("claude", listOf(project("/work", listOf(chat("a", 1000))))),
                Fake("other", listOf(project("/work", listOf(chat("b", 2000, "other"))))),
            ),
            home,
        )

        assertEquals(1, merged.size)
        assertEquals(listOf("b", "a"), merged.single().chats.map { it.sessionId })
    }

    @Test
    fun `a merged project is guessed only when every backend guessed it`() {
        val merged = mergedProjects(
            listOf(
                Fake("claude", listOf(project("/work", listOf(chat("a", 1)), guessed = true))),
                Fake("other", listOf(project("/work", listOf(chat("b", 2)), guessed = false))),
            ),
            home,
        )

        assertFalse(merged.single().guessed)
    }

    @Test
    fun `a merged project both backends guessed stays a guess`() {
        val merged = mergedProjects(
            listOf(
                Fake("claude", listOf(project("/work", listOf(chat("a", 1)), guessed = true))),
                Fake("other", listOf(project("/work", listOf(chat("b", 2)), guessed = true))),
            ),
            home,
        )

        assertTrue(merged.single().guessed)
    }

    @Test
    fun `a merged project is reachable when any backend found it so`() {
        val merged = mergedProjects(
            listOf(
                Fake("claude", listOf(project("/work", listOf(chat("a", 1)), reachable = false))),
                Fake("other", listOf(project("/work", listOf(chat("b", 2)), reachable = true))),
            ),
            home,
        )

        assertTrue(merged.single().reachable)
    }

    @Test
    fun `a merged project no backend could reach is out of reach`() {
        val merged = mergedProjects(
            listOf(
                Fake("claude", listOf(project("/work", listOf(chat("a", 1)), reachable = false))),
                Fake("other", listOf(project("/work", listOf(chat("b", 2)), reachable = false))),
            ),
            home,
        )

        assertFalse(merged.single().reachable)
    }

    @Test
    fun `projects come back most recently touched first`() {
        val merged = mergedProjects(
            listOf(
                Fake(
                    "claude",
                    listOf(
                        project("/old", listOf(chat("a", 1000))),
                        project("/new", listOf(chat("b", 3000))),
                    ),
                ),
            ),
            home,
        )

        assertEquals(listOf("/new", "/old"), merged.map { it.path })
    }

    @Test
    fun `a tab whose transcript is already listed adds no row`() {
        val projects = listOf(project(work.path, listOf(chat("a", 1000))))
        val tabs = listOf(OpenTab("a", work, "a tab", "claude"))

        assertEquals(projects, withOpenTabs(projects, tabs))
    }

    @Test
    fun `a tab with no transcript joins the project its directory names`() {
        val projects = listOf(project(work.path, listOf(chat("a", 1000))))
        val tabs = listOf(OpenTab("fresh", work, "a new chat", "claude"))

        val listed = withOpenTabs(projects, tabs).single()

        assertEquals(listOf("fresh", "a"), listed.chats.map { it.sessionId })
        assertEquals("a new chat", listed.chats.first().label)
        // The row names the backend running in the tab, so continuing it asks
        // the same CLI for its arguments.
        assertEquals("claude", listed.chats.first().backendId)
    }

    @Test
    fun `a path that ends in a separator is the directory a tab names`() {
        // A guessed path comes back from unflatten with the trailing separator
        // the folded name ended in, and a tab's directory carries none.
        val projects = listOf(project("/home/nev/", listOf(chat("a", 1000))))
        val tabs = listOf(OpenTab("fresh", File("/home/nev"), "a new chat", "claude"))

        val listed = withOpenTabs(projects, tabs)

        assertEquals(1, listed.size)
        assertEquals(listOf("fresh", "a"), listed.single().chats.map { it.sessionId })
    }

    @Test
    fun `a tab in a directory with no history brings its project with it`() {
        val projects = listOf(project(work.path, listOf(chat("a", 1000))))
        val tabs = listOf(OpenTab("fresh", elsewhere, "a new chat", "claude"))

        val listed = withOpenTabs(projects, tabs)

        // The synthetic chat is minted now, so its project sorts to the front.
        assertEquals(listOf(elsewhere.path, work.path), listed.map { it.path })
        val added = listed.first()
        assertEquals(listOf("fresh"), added.chats.map { it.sessionId })
        assertTrue(added.reachable)
        assertFalse(added.guessed)
    }

    @Test
    fun `two tabs in one unlisted directory are one project of two rows`() {
        val tabs = listOf(
            OpenTab("one", work, "first", "claude"),
            OpenTab("two", work, "second", "claude"),
        )

        val listed = withOpenTabs(emptyList(), tabs)

        assertEquals(1, listed.size)
        assertEquals(listOf("one", "two"), listed.single().chats.map { it.sessionId })
    }

    @Test
    fun `no tabs leaves the history as it was`() {
        val projects = listOf(project(work.path, listOf(chat("a", 1000))))

        assertEquals(projects, withOpenTabs(projects, emptyList()))
    }

    @Test
    fun `a free conversation is the one a fresh tab opens on`() {
        assertEquals("newest", freshSession("newest", emptySet()) { "minted" })
    }

    @Test
    fun `a conversation a process is already in is left to it`() {
        assertEquals("minted", freshSession("newest", setOf("newest")) { "minted" })
    }

    @Test
    fun `a directory with no history opens on a new id`() {
        assertEquals("minted", freshSession(null, emptySet()) { "minted" })
    }
}
