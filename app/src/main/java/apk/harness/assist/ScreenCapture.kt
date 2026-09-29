package apk.harness.assist

import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import java.io.File
import java.util.UUID

fun screenNodes(structure: AssistStructure): List<ScreenNode> =
    (0 until structure.windowNodeCount).mapNotNull { structure.getWindowNodeAt(it).rootViewNode?.let { root -> node(root, 0) } }

private fun node(view: AssistStructure.ViewNode, depth: Int): ScreenNode = ScreenNode(
    text = view.text?.toString(),
    description = view.contentDescription?.toString(),
    hint = view.hint,
    visible = view.visibility == View.VISIBLE,
    inputType = view.inputType,
    webDomain = view.webDomain,
    htmlType = view.htmlInfo?.attributes?.firstOrNull { it.first == "type" }?.second,
    // Deeper nodes are skipped by flattenScreen; not reading them bounds the recursion.
    children = if (depth >= SCREEN_DEPTH_LIMIT) emptyList() else (0 until view.childCount).map { node(view.getChildAt(it), depth + 1) },
)

/** The label of the app whose screen [structure] is, or its package name. */
fun appLabel(context: Context, structure: AssistStructure): String {
    val pkg = structure.activityComponent?.packageName ?: return "the screen"
    val pm = context.packageManager
    return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}

/** [bitmap] scaled to [targetSize] and encoded JPEG at quality 85 under the cache directory. */
fun encodeScreenshot(context: Context, bitmap: Bitmap): Attachment.Screenshot {
    val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val (width, height) = targetSize(software.width, software.height)
    val scaled = Bitmap.createScaledBitmap(software, width, height, true)
    val file = File(context.cacheDir, "assist/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
    try {
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    } finally {
        // The copies made here; the caller's bitmap is its own.
        if (scaled !== bitmap && scaled !== software) scaled.recycle()
        if (software !== bitmap) software.recycle()
    }
    return Attachment.Screenshot(file, file.length())
}
