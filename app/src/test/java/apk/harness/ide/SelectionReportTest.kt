package apk.harness.ide

import apk.harness.ui.markdownBlocks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionReportTest {

    @Test
    fun `nothing selected is sent as a range that holds nothing`() {
        // Not an absent key and not a null one: the CLI keeps the last range it
        // was given, so a message carrying no range leaves the old one standing.
        val params = selectionParams("/doc.md")
        val selection = params.getJSONObject("selection")

        assertEquals("/doc.md", params.getString("filePath"))
        assertEquals("", params.getString("text"))
        assertTrue(selection.getBoolean("isEmpty"))
        assertEquals(
            selection.getJSONObject("start").toString(),
            selection.getJSONObject("end").toString(),
        )
    }

    @Test
    fun `a range holding a line says it is not empty`() {
        val selection = selectionParams("/doc.md", 4, 7, "text").getJSONObject("selection")

        assertFalse(selection.getBoolean("isEmpty"))
    }

    @Test
    fun `the end position names the line after the last selected one`() {
        val selection = selectionParams("/doc.md", 4, 7, "text").getJSONObject("selection")
        assertEquals(4, selection.getJSONObject("start").getInt("line"))
        assertEquals(8, selection.getJSONObject("end").getInt("line"))
    }

    @Test
    fun `a one-line selection is a range of one line, not an empty one`() {
        // The case that degenerates: character 0 of the only selected line is
        // the point before it, so an inclusive end sends `1 to 0`.
        val selection = selectionParams("/doc.md", 0, 0, "text").getJSONObject("selection")
        assertEquals(0, selection.getJSONObject("start").getInt("line"))
        assertEquals(1, selection.getJSONObject("end").getInt("line"))
    }

    @Test
    fun `both ends sit at character zero`() {
        val selection = selectionParams("/doc.md", 4, 7, "text").getJSONObject("selection")
        assertEquals(0, selection.getJSONObject("start").getInt("character"))
        assertEquals(0, selection.getJSONObject("end").getInt("character"))
    }

    @Test
    fun `the params carry the path and the selected text`() {
        val params = selectionParams("/doc.md", 4, 7, "text")
        assertEquals("/doc.md", params.getString("filePath"))
        assertEquals("text", params.getString("text"))
    }

    @Test
    fun `an open document with no selection reports its path alone`() {
        assertEquals(Report("/doc.md", null, null), reportFor(CHAT, DOCUMENT, CHAT, null, null))
    }

    @Test
    fun `a selection reports its range and the text on those lines`() {
        val report = reportFor(CHAT, DOCUMENT, CHAT, Selection("/doc.md", 2, 4), null)
        assertEquals(Report("/doc.md", 2..4, "first para\n\nsecond para"), report)
    }

    @Test
    fun `a cleared selection reports the path alone`() {
        assertEquals(Report("/doc.md", null, null), reportFor(CHAT, DOCUMENT, CHAT, null, null))
    }

    @Test
    fun `no document reports nothing`() {
        assertNull(reportFor(CHAT, null, CHAT, null, null))
        assertNull(reportFor(CHAT, null, CHAT, Selection("/doc.md", 2, 4), null))
    }

    @Test
    fun `the reported text is sliced from the document at send time`() {
        val rewritten = DOCUMENT.copy(markdown = "# Title\n\nrewritten\n\nsecond para\n")
        assertEquals("rewritten", reportFor(CHAT, rewritten, CHAT, Selection("/doc.md", 2, 2), null)?.text)
    }

    @Test
    fun `the reported lines are zero-based against a document whose first line is line zero`() {
        // The first paragraph of a document that opens with one, taken through
        // the same arithmetic a tap goes through.
        val document = Surface.Document("/top.md", "top line\n\nand more\n", 0)
        val unit = unitOf(0, markdownBlocks(document.markdown).first())
        val report = reportFor(CHAT, document, CHAT, Selection(document.path, unit.lines.first, unit.lines.last), null)
        assertEquals(0..0, report?.lines)
        assertEquals("top line", report?.text)
    }

    @Test
    fun `a selection in a fenced source reports the file's line, not the fence's`() {
        // Body line 0 of a source file is document line 1. The wire carries
        // filePath, and the CLI reads that file, so the number beside the text
        // has to be the file's.
        val document = Surface.Document("/w/Foo.kt", "```kotlin\nval a = 1\nval b = 2\n```\n", -1)
        val report = reportFor(CHAT, document, CHAT, Selection("/w/Foo.kt", 1, 2), null)!!
        assertEquals(0..1, report.lines)
        assertEquals("val a = 1\nval b = 2", report.text)
    }

    @Test
    fun `a described file reports its path and no range`() {
        val document = Surface.Document("/w/blob.bin", "**Binary file**\n", null)
        val report = reportFor(CHAT, document, CHAT, Selection("/w/blob.bin", 0, 0), null)!!
        assertEquals(null, report.lines)
        assertEquals(null, report.text)
    }

    @Test
    fun `a document on screen is reported to the chat that owns it`() {
        assertEquals(
            Report("/doc.md", null, null),
            reportFor(CHAT, DOCUMENT, CHAT, null, null),
        )
    }

    @Test
    fun `a document on screen is not reported to any other chat`() {
        assertNull(reportFor(9L, DOCUMENT, CHAT, null, null))
    }

    @Test
    fun `a document no chat owns is reported to nobody`() {
        // A session started outside the app reaches the tools through the
        // app-wide token, which names no chat, and no chat holds NO_CHAT.
        assertNull(reportFor(CHAT, DOCUMENT, NO_CHAT, null, null))
    }

    @Test
    fun `an extended selection reports the wider range`() {
        assertEquals(
            Report("/doc.md", 0..4, "# Title\n\nfirst para\n\nsecond para"),
            reportFor(CHAT, DOCUMENT, CHAT, Selection("/doc.md", 0, 4), null),
        )
    }

    @Test
    fun `a parked document reports exactly what it reported on screen`() {
        val held = Selection("/doc.md", 2, 2)
        val onScreen = reportFor(CHAT, DOCUMENT, CHAT, held, null)
        val put = Surfaces.Parked(DOCUMENT, held)

        // Parking changes nothing the agent needs to know, which is the whole
        // of why minimising says nothing.
        assertEquals(onScreen, reportFor(CHAT, null, NO_CHAT, null, put))
    }

    @Test
    fun `two chats with documents parked at the same time each report their own`() {
        val mine = Surfaces.Parked(DOCUMENT, Selection("/doc.md", 2, 2))
        val theirs = Surfaces.Parked(Surface.Document("/other.md", "one\ntwo\n", 0), null)

        assertEquals("/doc.md", reportFor(CHAT, null, NO_CHAT, null, mine)?.path)
        assertEquals("/other.md", reportFor(8L, null, NO_CHAT, null, theirs)?.path)
    }

    @Test
    fun `a chat with nothing on screen and nothing parked is told nothing`() {
        assertNull(reportFor(CHAT, null, NO_CHAT, null, null))
    }

    @Test
    fun `a chat looking at another chat's document is told about its own parked one`() {
        // The chat on screen changed, so what CHAT owns is behind a band while
        // another chat's document is showing. CHAT is still told about its own.
        val mine = Surfaces.Parked(DOCUMENT, Selection("/doc.md", 4, 4))
        val report = reportFor(CHAT, Surface.Document("/other.md", "x\n", 0), 8L, null, mine)
        assertEquals(Report("/doc.md", 4..4, "second para"), report)
    }

    @Test
    fun `a closing entry reports the path with no selection`() {
        val closing = Surfaces.Parked(DOCUMENT, Selection("/doc.md", 2, 2), closing = true)
        // The same thing a cleared selection reports, and for the same reason:
        // the app has dropped the range and a block still naming it would be a
        // claim it no longer believes.
        assertEquals(
            Report("/doc.md", null, null),
            reportFor(CHAT, null, NO_CHAT, null, closing),
        )
    }

    @Test
    fun `an unclose reports the range again`() {
        val held = Selection("/doc.md", 2, 2)
        assertEquals(
            Report("/doc.md", 2..2, "first para"),
            reportFor(CHAT, DOCUMENT, CHAT, held, null),
        )
    }

    @Test
    fun `a closing entry that is gone retracts the range it was told`() {
        // The window ran out and the entry went. Nothing is left to describe
        // the document, and the chat is still holding a range, so the path is
        // reported once more with nothing on it.
        val told = Report("/doc.md", 2..4, "first para")

        assertEquals(
            Report("/doc.md", null, null),
            reportFor(CHAT, null, NO_CHAT, null, null, told),
        )
    }

    @Test
    fun `a chat told the path alone is told nothing more`() {
        val told = Report("/doc.md", null, null)

        assertNull(reportFor(CHAT, null, NO_CHAT, null, null, told))
    }

    @Test
    fun `a chat that was told nothing has nothing to hear`() {
        assertNull(reportFor(CHAT, null, NO_CHAT, null, null))
    }

    @Test
    fun `the retraction names the document that went, not the one on screen`() {
        // Another chat's document is on screen. This chat's went, and what it
        // is told about is its own.
        val told = Report("/mine.md", 1..1, "x")
        val theirs = Surface.Document("/theirs.md", "x\n", 0)

        assertEquals(
            Report("/mine.md", null, null),
            reportFor(CHAT, theirs, 8L, Selection("/theirs.md", 0, 0), null, told),
        )
    }

    @Test
    fun `a document still on screen is described by the state and not the ledger`() {
        val told = Report("/gone.md", 3..3, "stale")

        assertEquals(
            Report("/doc.md", 2..2, "first para"),
            reportFor(CHAT, DOCUMENT, CHAT, Selection("/doc.md", 2, 2), null, told),
        )
    }

    @Test
    fun `a document put away is described by the entry and not the ledger`() {
        val told = Report("/gone.md", 3..3, "stale")
        val put = Surfaces.Parked(DOCUMENT, Selection("/doc.md", 2, 2))

        assertEquals(
            Report("/doc.md", 2..2, "first para"),
            reportFor(CHAT, null, NO_CHAT, null, put, told),
        )
    }

    private companion object {
        const val CHAT = 7L

        val DOCUMENT = Surface.Document("/doc.md", "# Title\n\nfirst para\n\nsecond para\n", 0)
    }
}
