package apk.harness.assist

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShareDirectoriesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun names(directory: File) = directory.list().orEmpty().sorted()

    @Test
    fun `an unsent share's inbox and every share cache go, except those in use, and a sent share's inbox stays`() {
        val unsent = temp.newFolder("unsent")
        val inbox = temp.newFolder("inbox")
        val cache = temp.newFolder("share")
        for (id in listOf("abandoned", "open", "sent")) {
            File(inbox, "$id/nested").mkdirs()
            File(inbox, "$id/nested/file").writeText(id)
            File(cache, "$id/nested").mkdirs()
            File(cache, "$id/nested/image").writeText(id)
        }
        File(unsent, "abandoned").createNewFile()
        File(unsent, "open").createNewFile()

        clearAbandoned(unsent, inbox, cache, inUse = setOf("open"))

        assertEquals(listOf("open", "sent"), names(inbox))
        assertEquals(listOf("open"), names(cache))
        assertEquals(listOf("open"), names(unsent))
        assertEquals("open", File(inbox, "open/nested/file").readText())
    }

    @Test
    fun `nothing there is nothing to clear`() {
        val root = temp.root
        clearAbandoned(File(root, "unsent"), File(root, "inbox"), File(root, "share"), inUse = emptySet())
        assertEquals(emptyList<String>(), names(root))
    }
}
