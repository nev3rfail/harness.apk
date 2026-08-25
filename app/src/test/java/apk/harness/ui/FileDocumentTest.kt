package apk.harness.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDocumentTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a markdown extension is markdown`() {
        assertEquals(FileKind.Markdown, fileKind("/a/b/NOTES.md", ByteArray(0)))
        assertEquals(FileKind.Markdown, fileKind("/a/b/notes.MARKDOWN", ByteArray(0)))
    }

    @Test
    fun `a known extension names a language`() {
        assertEquals(FileKind.Text("kotlin"), fileKind("/a/Main.kt", ByteArray(0)))
        assertEquals(FileKind.Text("bash"), fileKind("/a/run.sh", ByteArray(0)))
    }

    @Test
    fun `an unknown or absent extension is unlabelled`() {
        assertEquals(FileKind.Text(null), fileKind("/a/Makefile", ByteArray(0)))
        assertEquals(FileKind.Text(null), fileKind("/a/thing.qqq", ByteArray(0)))
    }

    @Test
    fun `a NUL byte in the prefix is binary`() {
        val prefix = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 0)
        assertEquals(FileKind.Binary, fileKind("/a/libthing.so", prefix))
    }

    @Test
    fun `a markdown file with a NUL is still binary`() {
        assertEquals(FileKind.Binary, fileKind("/a/b.md", byteArrayOf(0)))
    }

    @Test
    fun `a fence is three backticks by default`() {
        assertEquals(3, fenceWidth("fun main() {}\n"))
        assertEquals(3, fenceWidth(""))
    }

    @Test
    fun `a fence outgrows the longest backtick run in the file`() {
        assertEquals(4, fenceWidth("a ``` b\n"))
        assertEquals(5, fenceWidth("a ```` b\n"))
        assertEquals(3, fenceWidth("an inline `x` only\n"))
    }

    @Test
    fun `markdown passes through unchanged`() {
        val source = "# Title\n\nBody.\n"
        assertEquals(source, fileDocument(FileKind.Markdown, source))
    }

    @Test
    fun `source is fenced and labelled`() {
        val document = fileDocument(FileKind.Text("kotlin"), "fun main() {}\n")
        assertEquals("```kotlin\nfun main() {}\n```\n", document)
    }

    @Test
    fun `an unlabelled fence has no info string`() {
        val document = fileDocument(FileKind.Text(null), "PREFIX=/usr\n")
        assertEquals("```\nPREFIX=/usr\n```\n", document)
    }

    @Test
    fun `a file with no trailing newline gets one`() {
        val document = fileDocument(FileKind.Text(null), "no newline")
        assertEquals("```\nno newline\n```\n", document)
    }

    @Test
    fun `a file containing a fence gets a longer one`() {
        val document = fileDocument(FileKind.Text(null), "before\n```\nafter\n")
        assertEquals("````\nbefore\n```\nafter\n````\n", document)
    }

    @Test
    fun `an empty file is an empty fence`() {
        assertEquals("```\n```\n", fileDocument(FileKind.Text(null), ""))
    }

    @Test
    fun `a fenced table is not lifted out as a table`() {
        val document = fileDocument(
            FileKind.Text(null),
            "| a | b |\n| --- | --- |\n| 1 | 2 |\n",
        )
        val blocks = markdownBlocks(document)

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Prose)
    }

    @Test
    fun `a text file reads as a fenced document`() {
        val file = folder.newFile("Main.kt")
        file.writeText("fun main() {}\n")

        assertEquals("```kotlin\nfun main() {}\n```\n", documentFor(file))
    }

    @Test
    fun `a markdown file reads as itself`() {
        val file = folder.newFile("NOTES.md")
        file.writeText("# Title\n")

        assertEquals("# Title\n", documentFor(file))
    }

    @Test
    fun `a binary file is described rather than read`() {
        val file = folder.newFile("thing.bin")
        file.writeBytes(byteArrayOf(1, 2, 0, 3))

        val document = documentFor(file)
        assertTrue(document.contains("Binary"))
        assertTrue(document.contains("4 bytes"))
        assertFalse(document.contains("```"))
    }

    @Test
    fun `a NUL late in the sniff window still classifies as binary`() {
        val file = folder.newFile("truncated.bin")
        val sniffBytes = 8000
        val bytes = ByteArray(sniffBytes + 500) { 'a'.code.toByte() }
        bytes[sniffBytes - 10] = 0
        file.writeBytes(bytes)

        val document = documentFor(file)
        assertTrue(document.contains("Binary"))
    }

    @Test
    fun `a file over the cap is described rather than read`() {
        val file = folder.newFile("big.txt")
        file.writeText("x".repeat((MAX_DOCUMENT_BYTES + 1).toInt()))

        val document = documentFor(file)
        assertTrue(document.contains("Too large"))
        assertTrue(document.contains("${MAX_DOCUMENT_BYTES + 1} bytes"))
        assertFalse(document.contains("```"))
    }

    @Test
    fun `a file exactly at the cap is read`() {
        val file = folder.newFile("edge.txt")
        file.writeText("x".repeat(MAX_DOCUMENT_BYTES.toInt()))

        assertTrue(documentFor(file).startsWith("```\nxxx"))
    }

    @Test
    fun `an unreadable file is described rather than thrown`() {
        val file = folder.newFile("locked.txt")
        file.writeText("secret\n")
        file.setReadable(false)

        try {
            val document = documentFor(file)
            assertTrue(document.contains("Cannot read"))
        } finally {
            file.setReadable(true)
        }
    }
}
