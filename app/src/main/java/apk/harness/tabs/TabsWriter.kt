package apk.harness.tabs

import java.io.File
import java.util.concurrent.Executors

/**
 * Writes the open tabs to [file], one snapshot at a time, off the caller's
 * thread.
 *
 * Only the newest snapshot waits: a burst of changes writes the last of them,
 * and an older one can never land after a newer one. Each write goes through a
 * temporary file and a rename, so a reader sees the old file or the new one.
 *
 * A writer belongs to one [generation]. A write finds the generation already in
 * the file first, and a writer older than it writes nothing: a screen whose end
 * was never reported cannot write over the screen that replaced it.
 */
class TabsWriter(
    private val file: File,
    private val generation: Long,
    /** Told of a write that failed; the next change writes again. */
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var pending: SavedTabs? = null
    private var closed = false
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tabs-writer").apply { isDaemon = true }
    }

    /** Queues [saved] as the next thing written, replacing whatever waited. */
    fun offer(saved: SavedTabs) {
        synchronized(lock) {
            if (closed) return
            val scheduled = pending != null
            pending = saved.copy(generation = generation)
            if (scheduled) return
        }
        executor.execute(::drain)
    }

    /**
     * Writes what still waits on the calling thread, and takes nothing more.
     *
     * The file is a few hundred bytes, so the caller -- a screen going away --
     * can afford to wait for it.
     */
    fun close() {
        synchronized(lock) { closed = true }
        executor.shutdown()
        drain()
    }

    // Taking a snapshot and writing it are one step under one monitor, so a
    // snapshot taken first is written first, whichever thread takes it.
    @Synchronized
    private fun drain() {
        val saved = synchronized(lock) { pending.also { pending = null } } ?: return
        write(saved)
    }

    private fun write(saved: SavedTabs) {
        val current = readSaved(file)?.generation ?: 0L
        if (current > generation) return
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(encode(saved))
            check(temporary.renameTo(file)) { "$temporary was not renamed over $file" }
        }.onFailure(onFailure)
    }
}
