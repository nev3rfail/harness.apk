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

        val rows = childRows(folder.root, emptyList(), noLinks)

        assertEquals(listOf("zzz", "aaa.txt"), rows.map { it.file.name })
        assertTrue(rows[0].isDirectory)
        assertFalse(rows[1].isDirectory)
    }

    @Test
    fun `names sort without regard to case`() {
        folder.newFile("Beta")
        folder.newFile("alpha")
        folder.newFile("Gamma")

        val rows = childRows(folder.root, emptyList(), noLinks)

        assertEquals(listOf("alpha", "Beta", "Gamma"), rows.map { it.file.name })
    }

    @Test
    fun `names differing only in case break the tie on the exact name`() {
        folder.newFile("Readme.md")
        folder.newFile("README.md")

        val rows = childRows(folder.root, emptyList(), noLinks)

        assertEquals(listOf("README.md", "Readme.md"), rows.map { it.file.name })
    }

    @Test
    fun `an empty directory yields no rows`() {
        val empty = folder.newFolder("empty")

        val rows = childRows(empty, emptyList(), noLinks)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a link to a file is marked as a link, not a directory`() {
        folder.newFile("target.txt")
        val isLink: (File) -> Boolean = { it.name == "target.txt" }

        val rows = childRows(folder.root, emptyList(), isLink)

        assertTrue(rows[0].isLink)
        assertFalse(rows[0].isDirectory)
    }

    @Test
    fun `dotfiles are listed`() {
        folder.newFile(".gitignore")

        val rows = childRows(folder.root, emptyList(), noLinks)

        assertEquals(listOf(".gitignore"), rows.map { it.file.name })
    }

    @Test
    fun `depth and the ancestor chain come from the levels above`() {
        folder.newFile("a")

        val ancestors = listOf(true, false, true, true)
        val row = childRows(folder.root, ancestors, noLinks).single()

        assertEquals(4, row.depth)
        assertEquals(ancestors, row.ancestorsContinue)
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

    @Test
    fun `the last of a directory's children is marked and the others are not`() {
        folder.newFile("a.txt")
        folder.newFile("b.txt")

        val rows = childRows(folder.root, emptyList(), noLinks)

        assertFalse(rows[0].isLastSibling)
        assertTrue(rows[1].isLastSibling)
    }

    @Test
    fun `an only child is the last of its siblings`() {
        folder.newFile("alone.txt")

        assertTrue(childRows(folder.root, emptyList(), noLinks).single().isLastSibling)
    }

    @Test
    fun `a directory with a sibling below it continues for the rows inside it`() {
        val one = folder.newFolder("one")
        File(one, "inside.txt").writeText("x")
        folder.newFile("z.txt")

        val rows = visibleRows(folder.root, setOf(one.path), noLinks)

        assertEquals(listOf("one", "inside.txt", "z.txt"), rows.map { it.file.name })
        assertEquals(listOf(true), rows[1].ancestorsContinue)
    }

    @Test
    fun `the last directory does not continue for the rows inside it`() {
        val last = folder.newFolder("last")
        File(last, "inside.txt").writeText("x")

        val rows = visibleRows(folder.root, setOf(last.path), noLinks)

        assertEquals(listOf(false), rows[1].ancestorsContinue)
    }

    @Test
    fun `the ancestor chain grows one entry per level`() {
        val one = folder.newFolder("one")
        val two = File(one, "two").also { it.mkdir() }
        File(two, "three.txt").writeText("x")

        val rows = visibleRows(folder.root, setOf(one.path, two.path), noLinks)

        assertEquals(emptyList<Boolean>(), rows[0].ancestorsContinue)
        assertEquals(listOf(false), rows[1].ancestorsContinue)
        assertEquals(listOf(false, false), rows[2].ancestorsContinue)
        assertEquals(listOf(0, 1, 2), rows.map { it.depth })
    }

    @Test
    fun `a row following a collapsed directory carries no ancestors`() {
        folder.newFolder("shut").also { File(it, "unseen.txt").writeText("x") }
        folder.newFile("after.txt")

        val rows = visibleRows(folder.root, emptySet(), noLinks)

        assertEquals(listOf("shut", "after.txt"), rows.map { it.file.name })
        assertEquals(emptyList<Boolean>(), rows[1].ancestorsContinue)
        assertTrue(rows[1].isLastSibling)
    }
}
