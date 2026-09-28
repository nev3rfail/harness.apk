package apk.harness.assist

import android.content.Context
import android.content.Intent
import apk.harness.MainActivity
import apk.harness.tabs.ACTION_OPEN
import apk.harness.tabs.EXTRA_DIRECTORY
import apk.harness.tabs.EXTRA_SESSION
import java.io.File

/**
 * OPEN for [directory], and [session] when there is one, addressed to this
 * channel's MainActivity: every channel answers the same action.
 */
fun openTab(context: Context, directory: File, session: String?): Intent =
    Intent(ACTION_OPEN)
        .setClass(context, MainActivity::class.java)
        .putExtra(EXTRA_DIRECTORY, directory.absolutePath)
        .apply { if (session != null) putExtra(EXTRA_SESSION, session) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
