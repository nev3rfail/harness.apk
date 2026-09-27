package apk.harness.tabs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedTabsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val c = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    private fun tab(id: String, directory: String = "/app/files") = SavedTab(directory, id, "claude", "home")

    // ---- the file ----

    @Test
    fun `the tabs are read back as they were written`() {
        val saved = SavedTabs(3, front = b, tabs = listOf(tab(a), tab(b, "/work")))

        assertEquals(saved, decode(encode(saved)))
    }

    @Test
    fun `text that is not the document is no tabs`() {
        assertNull(decode(""))
        assertNull(decode("half a fi"))
        assertNull(decode("""{"generation":1}"""))
    }

    @Test
    fun `a tab whose id is not a session id is dropped`() {
        val text = encode(SavedTabs(1, front = "x --settings evil", tabs = listOf(tab(a)))).replace(a, "x --settings evil")

        val read = decode(text)!!
        assertEquals(emptyList<SavedTab>(), read.tabs)
        assertNull(read.front)
    }

    // ---- the writer ----

    @Test
    fun `closing the writer writes what is still waiting`() {
        val file = File(folder.root, "tabs.json")
        val writer = TabsWriter(file, generation = 1)

        writer.offer(SavedTabs(0, a, listOf(tab(a))))
        writer.close()

        assertEquals(listOf(a), readSaved(file)!!.tabs.map { it.sessionId })
        assertEquals(1L, readSaved(file)!!.generation)
    }

    @Test
    fun `the newest of several snapshots is what lands`() {
        val file = File(folder.root, "tabs.json")
        val writer = TabsWriter(file, generation = 1)

        writer.offer(SavedTabs(0, a, listOf(tab(a))))
        writer.offer(SavedTabs(0, b, listOf(tab(a), tab(b))))
        writer.offer(SavedTabs(0, c, listOf(tab(c))))
        writer.close()

        assertEquals(listOf(c), readSaved(file)!!.tabs.map { it.sessionId })
    }

    @Test
    fun `a closed writer takes nothing more`() {
        val file = File(folder.root, "tabs.json")
        val writer = TabsWriter(file, generation = 1)
        writer.offer(SavedTabs(0, a, listOf(tab(a))))
        writer.close()

        writer.offer(SavedTabs(0, b, listOf(tab(b))))
        Thread.sleep(100)

        assertEquals(listOf(a), readSaved(file)!!.tabs.map { it.sessionId })
    }

    @Test
    fun `a writer of an older generation writes nothing over a newer one`() {
        val file = File(folder.root, "tabs.json")
        TabsWriter(file, generation = 2).apply {
            offer(SavedTabs(0, b, listOf(tab(b))))
            close()
        }

        TabsWriter(file, generation = 1).apply {
            offer(SavedTabs(0, a, listOf(tab(a))))
            close()
        }

        assertEquals(listOf(b), readSaved(file)!!.tabs.map { it.sessionId })
    }

    @Test
    fun `a file is replaced where one is already there`() {
        val file = File(folder.root, "tabs.json").apply { writeText("{}") }
        TabsWriter(file, generation = 1).apply {
            offer(SavedTabs(0, a, listOf(tab(a))))
            close()
        }

        assertTrue(readSaved(file)!!.tabs.isNotEmpty())
    }

    // ---- the plan ----

    private fun plan(
        saved: SavedTabs,
        live: Set<String> = emptySet(),
        allFiles: Boolean = true,
        exists: Set<String> = setOf("/app/files", "/work", "/storage/emulated/0/p"),
        recency: Map<String, Long> = emptyMap(),
        allDormant: Boolean = false,
    ) = restorePlan(
        saved,
        live = live,
        allFiles = allFiles,
        insideApp = { it.startsWith("/app") },
        exists = { it in exists },
        recency = { recency[it] ?: 0L },
        allDormant = allDormant,
    )

    @Test
    fun `the tab in front starts and the rest come back parked`() {
        val out = plan(SavedTabs(1, front = b, tabs = listOf(tab(a), tab(b), tab(c, "/work"))))

        assertEquals(b, out.start!!.sessionId)
        assertEquals(setOf(a, c), out.dormant.map { it.sessionId }.toSet())
    }

    @Test
    fun `a directory in the app that is gone is dropped`() {
        val out = plan(SavedTabs(1, front = a, tabs = listOf(tab(a, "/app/gone"), tab(b))))

        assertEquals(listOf(a), out.dropped.map { it.sessionId })
        assertEquals(b, out.start!!.sessionId)
    }

    @Test
    fun `a directory outside the app without the grant is parked unexamined`() {
        val out = plan(
            SavedTabs(1, front = a, tabs = listOf(tab(a, "/storage/emulated/0/unseen"), tab(b))),
            allFiles = false,
            exists = setOf("/app/files"),
        )

        assertEquals(listOf(a), out.dormant.map { it.sessionId })
        assertEquals(emptyList<SavedTab>(), out.dropped)
        assertEquals(b, out.start!!.sessionId)
    }

    @Test
    fun `a directory outside the app that is missing is kept dormant, not dropped`() {
        val out = plan(
            SavedTabs(1, front = a, tabs = listOf(tab(a, "/storage/usb/p"), tab(b))),
            allFiles = true,
            exists = setOf("/app/files"),
        )

        assertEquals(listOf(a), out.dormant.map { it.sessionId })
        assertEquals(emptyList<SavedTab>(), out.dropped)
    }

    @Test
    fun `a conversation still live in an earlier agent is parked`() {
        val out = plan(SavedTabs(1, front = a, tabs = listOf(tab(a), tab(b))), live = setOf(a))

        assertEquals(b, out.start!!.sessionId)
        assertEquals(listOf(a), out.dormant.map { it.sessionId })
    }

    @Test
    fun `a front that cannot start hands the front to the most recent`() {
        val out = plan(
            SavedTabs(1, front = a, tabs = listOf(tab(a), tab(b), tab(c))),
            live = setOf(a),
            recency = mapOf(b to 10, c to 20),
        )

        assertEquals(c, out.start!!.sessionId)
    }

    @Test
    fun `a request from outside parks everything`() {
        val out = plan(SavedTabs(1, front = a, tabs = listOf(tab(a), tab(b))), allDormant = true)

        assertNull(out.start)
        assertEquals(setOf(a, b), out.dormant.map { it.sessionId }.toSet())
    }

    @Test
    fun `nothing that can start starts nothing`() {
        val out = plan(SavedTabs(1, front = a, tabs = listOf(tab(a))), live = setOf(a))

        assertNull(out.start)
    }

    // ---- the request ----

    @Test
    fun `a directory alone opens a new conversation there`() {
        assertEquals(OpenRequest.Open("/work", null), openRequest(ACTION_OPEN, "/work", null))
    }

    @Test
    fun `a directory and a session reopen that conversation`() {
        assertEquals(OpenRequest.Open("/work", a), openRequest(ACTION_OPEN, "/work", a))
    }

    @Test
    fun `another action is no request`() {
        assertNull(openRequest("android.intent.action.MAIN", "/work", null))
        assertNull(openRequest(null, null, null))
    }

    @Test
    fun `a directory that is missing, relative or climbing is refused`() {
        assertTrue(openRequest(ACTION_OPEN, null, null) is OpenRequest.Refused)
        assertTrue(openRequest(ACTION_OPEN, "", null) is OpenRequest.Refused)
        assertTrue(openRequest(ACTION_OPEN, "work", null) is OpenRequest.Refused)
        assertTrue(openRequest(ACTION_OPEN, "/work/../etc", null) is OpenRequest.Refused)
    }

    @Test
    fun `a session that is not a session id is refused`() {
        assertTrue(openRequest(ACTION_OPEN, "/work", "x --dangerously-skip-permissions") is OpenRequest.Refused)
        assertTrue(openRequest(ACTION_OPEN, "/work", "--resume") is OpenRequest.Refused)
    }
}
