package apk.harness.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStageTest {

    @Test
    fun `a data spelling is answered with the user spelling beside it`() {
        assertEquals(
            listOf("/data/data/apk.harness/files", "/data/user/0/apk.harness/files"),
            bothSpellings("apk.harness", "/data/data/apk.harness/files"),
        )
    }

    @Test
    fun `a user spelling is answered with the data spelling beside it`() {
        assertEquals(
            listOf("/data/user/0/apk.harness/files", "/data/data/apk.harness/files"),
            bothSpellings("apk.harness", "/data/user/0/apk.harness/files"),
        )
    }

    @Test
    fun `a nested directory keeps everything under the package`() {
        assertEquals(
            listOf("/data/data/apk.harness/cache/x", "/data/user/0/apk.harness/cache/x"),
            bothSpellings("apk.harness", "/data/data/apk.harness/cache/x"),
        )
    }

    @Test
    fun `a directory that is neither is answered with itself alone`() {
        assertEquals(
            listOf("/storage/emulated/0/Download"),
            bothSpellings("apk.harness", "/storage/emulated/0/Download"),
        )
    }

    @Test
    fun `a sibling of the package directory is not mistaken for it`() {
        assertEquals(
            listOf("/data/data/apk.harnessing/files"),
            bothSpellings("apk.harness", "/data/data/apk.harnessing/files"),
        )
    }
}
