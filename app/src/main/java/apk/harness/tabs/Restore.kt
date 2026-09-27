package apk.harness.tabs

import apk.harness.agents.isSessionId

/**
 * What a start does with the tabs that were open: the one it starts, the ones
 * it brings back parked, and the ones it drops.
 */
data class RestorePlan(
    val start: SavedTab?,
    val parked: List<SavedTab>,
    val dropped: List<SavedTab>,
)

/**
 * The plan for [saved], decided before anything starts.
 *
 * - A directory outside the app with no all-files access is parked without being
 *   looked at: without the grant it may read as absent.
 * - A directory inside the app that is gone is dropped. Its conversation is still
 *   in the history.
 * - A conversation [live] names -- an agent from an earlier screen may still be
 *   running or still writing -- is parked, so it never gets a second writer.
 * - The tab in front starts. When it cannot, the one that can start whose
 *   transcript moved last, by [recency], starts instead.
 * - Everything else is parked. With [parkAll] -- a request from outside that
 *   names the tab it wants started -- nothing starts.
 */
fun restorePlan(
    saved: SavedTabs,
    live: Set<String>,
    allFiles: Boolean,
    insideApp: (String) -> Boolean,
    exists: (String) -> Boolean,
    recency: (String) -> Long,
    parkAll: Boolean,
): RestorePlan {
    val dropped = mutableListOf<SavedTab>()
    val startable = mutableListOf<SavedTab>()
    val parked = mutableListOf<SavedTab>()

    for (tab in saved.tabs.distinctBy { it.sessionId }) {
        when {
            !isSessionId(tab.sessionId) -> dropped += tab
            !insideApp(tab.directory) && !allFiles -> parked += tab
            !exists(tab.directory) -> dropped += tab
            tab.sessionId in live -> parked += tab
            else -> startable += tab
        }
    }

    if (parkAll) return RestorePlan(start = null, parked = parked + startable, dropped = dropped)

    val start = startable.firstOrNull { it.sessionId == saved.front }
        ?: startable.maxByOrNull { recency(it.sessionId) }
    return RestorePlan(start = start, parked = parked + (startable - listOfNotNull(start)), dropped = dropped)
}

/** A request to open an agent from outside the app. */
sealed interface OpenRequest {
    /** Open [directory], on [session] when one is named. */
    data class Open(val directory: String, val session: String?) : OpenRequest

    /** Refused, and [reason] says why. */
    data class Refused(val reason: String) : OpenRequest
}

/** The action another app sends to open an agent in a directory. */
const val ACTION_OPEN = "apk.harness.action.OPEN"
const val EXTRA_DIRECTORY = "apk.harness.extra.DIRECTORY"
const val EXTRA_SESSION = "apk.harness.extra.SESSION"

/**
 * The request [action] and its extras make, before any check that needs the
 * filesystem.
 *
 * The directory must be an absolute path with no `..` segment, and a session
 * must be a session id. Whether the directory exists is asked afterwards, with
 * the all-files grant in hand, since without it a path in shared storage may not
 * resolve.
 */
fun openRequest(action: String?, directory: String?, session: String?): OpenRequest? {
    if (action != ACTION_OPEN) return null
    if (directory.isNullOrEmpty()) return OpenRequest.Refused("No directory to open was named.")
    if (!directory.startsWith("/") || directory.split('/').any { it == ".." }) {
        return OpenRequest.Refused("$directory is not an absolute path.")
    }
    if (session != null && !isSessionId(session)) {
        return OpenRequest.Refused("$session is not a conversation id.")
    }
    return OpenRequest.Open(directory, session)
}
