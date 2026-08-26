package apk.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalSettingsTest {

    @Test
    fun `a declared budget is read`() {
        assertEquals(
            10_000_000L,
            TerminalSettings.scrollbackBytes(
                """
                {
                  "theme": "dark",
                  "env": {
                    "HARNESS_TERMINAL_SCROLLBACK": "10000000"
                  }
                }
                """,
            ),
        )
    }

    @Test
    fun `a budget written as a number is read`() {
        assertEquals(
            4_000_000L,
            TerminalSettings.scrollbackBytes("""{"env":{"HARNESS_TERMINAL_SCROLLBACK":4000000}}"""),
        )
    }

    @Test
    fun `a budget beside other variables is read`() {
        assertEquals(
            512L,
            TerminalSettings.scrollbackBytes(
                """
                {
                  "env": {
                    "PREFIX": "/data/data/apk.harness/root/usr",
                    "HARNESS_TERMINAL_SCROLLBACK": "512",
                    "TERMUX_VERSION": "0.118.3"
                  },
                  "enabledPlugins": { "superpowers@claude-plugins-official": true }
                }
                """,
            ),
        )
    }

    @Test
    fun `no env block leaves the library default`() {
        assertNull(TerminalSettings.scrollbackBytes("""{"theme":"dark"}"""))
    }

    @Test
    fun `a disabled env block leaves the library default`() {
        // The name is deliberately not `env`, which is how a block is held back.
        assertEquals(
            null,
            TerminalSettings.scrollbackBytes(
                """{"_env_disabled_pending_worker_cap":{"HARNESS_TERMINAL_SCROLLBACK":"10000000"}}""",
            ),
        )
    }

    @Test
    fun `an env block without the name leaves the library default`() {
        assertNull(TerminalSettings.scrollbackBytes("""{"env":{"PREFIX":"/usr"}}"""))
    }

    @Test
    fun `a value that is not a number leaves the library default`() {
        assertEquals(
            null,
            TerminalSettings.scrollbackBytes("""{"env":{"HARNESS_TERMINAL_SCROLLBACK":"lots"}}"""),
        )
    }

    @Test
    fun `zero and negative budgets leave the library default`() {
        assertEquals(
            null,
            TerminalSettings.scrollbackBytes("""{"env":{"HARNESS_TERMINAL_SCROLLBACK":"0"}}"""),
        )
        assertEquals(
            null,
            TerminalSettings.scrollbackBytes("""{"env":{"HARNESS_TERMINAL_SCROLLBACK":"-1"}}"""),
        )
    }

    @Test
    fun `a truncated document leaves the library default`() {
        assertEquals(
            null,
            TerminalSettings.scrollbackBytes("""{"env":{"HARNESS_TERMINAL_SCROLLBACK":"10000000"""),
        )
    }

    @Test
    fun `an empty value leaves the library default`() {
        assertNull(TerminalSettings.scrollbackBytes("""{"env":{"HARNESS_TERMINAL_SCROLLBACK":""}}"""))
        assertNull(TerminalSettings.scrollbackBytes("""{"env":{"HARNESS_TERMINAL_SCROLLBACK":"   "}}"""))
    }

    @Test
    fun `an empty document leaves the library default`() {
        assertNull(TerminalSettings.scrollbackBytes(""))
    }

    @Test
    fun `a nested object inside env does not confuse the reader`() {
        assertEquals(
            777L,
            TerminalSettings.scrollbackBytes(
                """
                {
                  "env": {
                    "NESTED": { "not": "a budget" },
                    "HARNESS_TERMINAL_SCROLLBACK": "777"
                  },
                  "after": 1
                }
                """,
            ),
        )
    }
}
