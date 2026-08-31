package apk.harness

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PickedTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a folder on the primary volume is the volume root and the relative part`() {
        assertEquals(
            "/storage/emulated/0/Documents/notes",
            documentPath(EXTERNAL_STORAGE, "primary:Documents/notes", PRIMARY_ROOT),
        )
    }

    @Test
    fun `a volume that is not the primary one is mounted under storage`() {
        assertEquals(
            "/storage/1A2B-3C4D/notes",
            documentPath(EXTERNAL_STORAGE, "1A2B-3C4D:notes", PRIMARY_ROOT),
        )
    }

    @Test
    fun `an id naming no relative part is the volume root itself`() {
        assertEquals(PRIMARY_ROOT, documentPath(EXTERNAL_STORAGE, "primary:", PRIMARY_ROOT))
    }

    @Test
    fun `an authority that is not a filesystem names nothing`() {
        assertNull(
            documentPath(
                "com.android.providers.downloads.documents",
                "primary:Documents",
                PRIMARY_ROOT,
            ),
        )
    }

    @Test
    fun `an id carrying no volume names nothing`() {
        assertNull(documentPath(EXTERNAL_STORAGE, "Documents/notes", PRIMARY_ROOT))
        assertNull(documentPath(EXTERNAL_STORAGE, ":Documents", PRIMARY_ROOT))
    }

    @Test
    fun `a relative part that climbs out of its volume names nothing`() {
        assertNull(documentPath(EXTERNAL_STORAGE, "primary:../other", PRIMARY_ROOT))
        assertNull(documentPath(EXTERNAL_STORAGE, "primary:Documents/../../other", PRIMARY_ROOT))
    }

    @Test
    fun `a relative part that climbs and comes back stays in its volume`() {
        assertEquals(
            "/storage/emulated/0/other",
            documentPath(EXTERNAL_STORAGE, "primary:Documents/../other", PRIMARY_ROOT),
        )
    }

    @Test
    fun `a name nobody has taken is the name itself`() {
        assertEquals("shot.png", freeName("shot.png", taken = emptySet()))
    }

    @Test
    fun `a name in use is numbered before its extension`() {
        assertEquals("shot-1.png", freeName("shot.png", taken = setOf("shot.png")))
        assertEquals(
            "shot-2.png",
            freeName("shot.png", taken = setOf("shot.png", "shot-1.png")),
        )
        assertEquals(
            "shot-3.png",
            freeName("shot.png", taken = setOf("shot.png", "shot-1.png", "shot-2.png")),
        )
    }

    @Test
    fun `a name with no extension is numbered at its end`() {
        assertEquals("README-1", freeName("README", taken = setOf("README")))
    }

    @Test
    fun `a leading dot is part of the name rather than an extension`() {
        assertEquals(".bashrc-1", freeName(".bashrc", taken = setOf(".bashrc")))
    }

    @Test
    fun `a name with several dots is numbered before the last of them`() {
        assertEquals(
            "archive.tar-1.gz",
            freeName("archive.tar.gz", taken = setOf("archive.tar.gz")),
        )
    }

    @Test
    fun `the provider's documents root is the primary volume's documents`() {
        assertEquals(
            "/storage/emulated/0/Documents/notes",
            documentPath(EXTERNAL_STORAGE, "home:notes", PRIMARY_ROOT),
        )
        assertEquals(
            "/storage/emulated/0/Documents",
            documentPath(EXTERNAL_STORAGE, "home:", PRIMARY_ROOT),
        )
    }

    @Test
    fun `a document under the documents root cannot climb out of it`() {
        assertNull(documentPath(EXTERNAL_STORAGE, "home:../DCIM", PRIMARY_ROOT))
    }

    @Test
    fun `a directory that can be written to is answered canonically`() {
        val picked = folder.newFolder("notes")

        val opened = openFolder(picked.path, folder.root.path)

        assertEquals(Picked.Folder(picked.canonicalPath), opened)
    }

    @Test
    fun `the probe leaves nothing behind`() {
        val picked = folder.newFolder("notes")

        openFolder(picked.path, folder.root.path)

        assertEquals(emptyList<String>(), picked.list()!!.toList())
    }

    @Test
    fun `a file is not a directory to work in`() {
        val file = folder.newFile("notes.txt")

        assertEquals(
            Picked.Refused("That is not a directory."),
            openFolder(file.path, folder.root.path),
        )
    }

    @Test
    fun `a directory that is not there is refused`() {
        val absent = File(folder.root, "absent")

        assertTrue(openFolder(absent.path, folder.root.path) is Picked.Refused)
    }

    @Test
    fun `a directory outside the root it was picked from is refused`() {
        val outside = folder.newFolder("outside")
        val root = folder.newFolder("root")

        assertEquals(
            Picked.Refused("That folder leads outside ${root.canonicalPath}."),
            openFolder(outside.path, root.path),
        )
    }

    @Test
    fun `a name a directory can hold is left as it is`() {
        assertEquals("shot.png", plainName("shot.png"))
    }

    @Test
    fun `a separator in a name folds away`() {
        assertEquals("-etc-passwd", plainName("/etc/passwd"))
        assertEquals("a-b", plainName("""a\b"""))
    }

    @Test
    fun `a space folds away, so an at-reference ends where the name does`() {
        assertEquals("my-shot.png", plainName("my shot.png"))
    }

    @Test
    fun `a cut lands on a character built from two of them`() {
        val long = "a".repeat(197) + "\uD83D\uDE00\uD83D\uDE00"

        val name = plainName(long)

        assertTrue(name.toByteArray().size <= 200)
        assertEquals(name, String(name.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun `the volume root is answered on its own`() {
        assertEquals(PRIMARY_ROOT, documentRoot(EXTERNAL_STORAGE, "primary:x", PRIMARY_ROOT))
        assertEquals(
            "/storage/emulated/0/Documents",
            documentRoot(EXTERNAL_STORAGE, "home:x", PRIMARY_ROOT),
        )
        assertEquals(
            "/storage/1A2B-3C4D",
            documentRoot(EXTERNAL_STORAGE, "1A2B-3C4D:x", PRIMARY_ROOT),
        )
        assertNull(documentRoot("com.example.provider", "primary:x", PRIMARY_ROOT))
        assertNull(documentRoot(EXTERNAL_STORAGE, "noColon", PRIMARY_ROOT))
    }

    @Test
    fun `a probe already in the folder is left alone`() {
        val picked = folder.newFolder("notes")
        val existing = File(picked, ".harness-probe")
        existing.writeText("someone else's")

        openFolder(picked.path, folder.root.path)

        assertEquals("someone else's", existing.readText())
    }

    @Test
    fun `a name that is only dots names no file`() {
        assertEquals("document", plainName(".."))
        assertEquals("document", plainName("."))
        assertEquals("document", plainName(""))
        assertEquals("document", plainName("   "))
    }

    @Test
    fun `a name longer than a filesystem takes is cut`() {
        val long = "a".repeat(400) + ".png"

        val name = plainName(long)

        assertTrue(name.toByteArray().size <= 200)
        assertTrue(name.startsWith("aaaa"))
    }

    @Test
    fun `a cut lands on a character rather than inside one`() {
        val long = "\u00e9".repeat(300)

        val name = plainName(long)

        assertTrue(name.toByteArray().size <= 200)
        assertEquals(name, String(name.toByteArray(), Charsets.UTF_8))
    }

    private fun freeName(name: String, taken: Set<String>): String =
        freeName(name) { it in taken }

    private companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        const val PRIMARY_ROOT = "/storage/emulated/0"
    }
}
