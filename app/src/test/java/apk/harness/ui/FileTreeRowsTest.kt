package apk.harness.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTreeRowsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val noLinks: (File) -> Boolean = { false }

    @Test
    fun `directories come before files`() {
        folder.newFile("aaa.txt")
        folder.newFolder("zzz")

        val rows = childRows(folder.root, 0, noLinks)

        assertEquals(listOf("zzz", "aaa.txt"), rows.map { it.file.name })
        assertTrue(rows[0].isDirectory)
        assertFalse(rows[1].isDirectory)
    }

    @Test
    fun `names sort without regard to case`() {
        folder.newFile("Beta")
        folder.newFile("alpha")
        folder.newFile("Gamma")

        val rows = childRows(folder.root, 0, noLinks)

        assertEquals(listOf("alpha", "Beta", "Gamma"), rows.map { it.file.name })
    }

    @Test
    fun `dotfiles are listed`() {
        folder.newFile(".gitignore")

        val rows = childRows(folder.root, 0, noLinks)

        assertEquals(listOf(".gitignore"), rows.map { it.file.name })
    }

    @Test
    fun `depth is carried onto every child`() {
        folder.newFile("a")

        assertEquals(4, childRows(folder.root, 4, noLinks).single().depth)
    }

    @Test
    fun `an unreadable directory yields one marked row and no children`() {
        val locked = folder.newFolder("locked")
        File(locked, "hidden.txt").writeText("x")
        locked.setReadable(false)

        val rows = visibleRows(folder.root, setOf(locked.path), noLinks)

        assertEquals(1, rows.size)
        assertTrue(rows[0].unreadable)
    }

    @Test
    fun `only expanded directories contribute children`() {
        val open = folder.newFolder("open")
        File(open, "inside.txt").writeText("x")
        val shut = folder.newFolder("shut")
        File(shut, "unseen.txt").writeText("x")

        val rows = visibleRows(folder.root, setOf(open.path), noLinks)

        assertEquals(listOf("open", "inside.txt", "shut"), rows.map { it.file.name })
        assertEquals(listOf(0, 1, 0), rows.map { it.depth })
    }

    @Test
    fun `a link to a directory is never expanded`() {
        val target = folder.newFolder("target")
        File(target, "inside.txt").writeText("x")
        val isLink: (File) -> Boolean = { it.name == "target" }

        val rows = visibleRows(folder.root, setOf(target.path), isLink)

        assertEquals(listOf("target"), rows.map { it.file.name })
        assertTrue(rows[0].isLink)
    }

    @Test
    fun `an expanded path that no longer exists is ignored`() {
        folder.newFile("a")

        val rows = visibleRows(folder.root, setOf("/nowhere/at/all"), noLinks)

        assertEquals(listOf("a"), rows.map { it.file.name })
    }

    @Test
    fun `nesting goes as deep as the expanded set`() {
        val one = folder.newFolder("one")
        val two = File(one, "two").also { it.mkdir() }
        File(two, "three.txt").writeText("x")

        val rows = visibleRows(folder.root, setOf(one.path, two.path), noLinks)

        assertEquals(listOf("one", "two", "three.txt"), rows.map { it.file.name })
        assertEquals(listOf(0, 1, 2), rows.map { it.depth })
    }
}
