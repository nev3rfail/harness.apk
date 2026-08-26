package apk.harness

import java.io.File

/**
 * Settings the app reads for itself out of the agent's own configuration.
 *
 * The terminal's history budget has to be known before the terminal exists, and
 * Android offers nothing that can put a variable into the app process's
 * environment: there is no shell between the launcher and the process. The
 * `env` block in `.claude/settings.json` is the agent's mechanism and reaches
 * the agent's process, not this one, so the value is read from the file rather
 * than from the environment.
 *
 * The reader is deliberately narrow. It looks for one name inside the `env`
 * object and ignores everything else, so a settings file the agent rewrites for
 * its own reasons cannot break the terminal.
 */
object TerminalSettings {

    /** History budget in bytes. */
    const val SCROLLBACK = "HARNESS_TERMINAL_SCROLLBACK"

    /**
     * The scrollback budget declared for this home, or null when none is.
     *
     * Null is the answer for a missing file, a missing `env` block, a missing
     * name, an empty value, and a value that is not a positive number. Every one
     * of those means the same thing: nothing was asked for, so the terminal
     * library's own default budget applies. It does **not** mean no history --
     * that library reads a budget of zero as "keep no scrollback at all", which
     * is why the absence is spelled as an absence here rather than as a number.
     */
    fun scrollbackBytes(home: File): Long? {
        val file = File(home, SETTINGS_PATH)
        val text = try {
            if (file.isFile) file.readText() else return null
        } catch (e: Exception) {
            return null
        }
        return scrollbackBytes(text)
    }

    /**
     * The same value read out of a settings document, so the parsing can be
     * tested without a file. Null carries the same meaning as above.
     */
    fun scrollbackBytes(settings: String): Long? {
        val env = objectBody(settings, ENV_KEY) ?: return null
        val raw = stringOrNumber(env, SCROLLBACK) ?: return null
        val value = raw.trim().toLongOrNull() ?: return null
        // A declared zero is treated as unasked rather than as "no history", because
        // the terminal library would read it as the latter.
        return if (value > 0L) value else null
    }

    /**
     * The text between the braces of the object a key names, or null when the
     * key is absent or names something else. Brace counting rather than a
     * pattern, because the object holds objects.
     */
    private fun objectBody(json: String, key: String): String? {
        var at = json.indexOf("\"$key\"")
        if (at < 0) return null
        at = json.indexOf(':', at + key.length + 2)
        if (at < 0) return null
        val open = json.indexOf('{', at)
        if (open < 0) return null
        // A colon followed by anything but an object is a value of another kind.
        if (json.substring(at + 1, open).isNotBlank()) return null

        var depth = 0
        var quoted = false
        var escaped = false
        for (i in open until json.length) {
            val c = json[i]
            when {
                escaped -> escaped = false
                c == '\\' && quoted -> escaped = true
                c == '"' -> quoted = !quoted
                quoted -> {}
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return json.substring(open + 1, i)
                }
            }
        }
        return null
    }

    /**
     * The value a key holds, whether it is written as a string or bare. Both
     * spellings appear in these files, and a budget is a number either way.
     */
    private fun stringOrNumber(body: String, key: String): String? {
        var at = body.indexOf("\"$key\"")
        if (at < 0) return null
        at = body.indexOf(':', at + key.length + 2)
        if (at < 0) return null

        var i = at + 1
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length) return null

        if (body[i] == '"') {
            val end = body.indexOf('"', i + 1)
            return if (end < 0) null else body.substring(i + 1, end)
        }
        val end = i + (body.substring(i).indexOfFirst { it == ',' || it == '}' || it == '\n' }
            .takeIf { it >= 0 } ?: (body.length - i))
        return body.substring(i, end)
    }

    private const val ENV_KEY = "env"
    private const val SETTINGS_PATH = ".claude/settings.json"
}
