package apk.harness.assist

import java.io.File

/** Something that goes with the next message, and its line in the turn. */
sealed interface Attachment {
    val line: String

    data class ScreenText(val app: String, val text: String) : Attachment {
        override val line get() = "Screen text from $app"
    }

    /** An encoded screenshot in [file], [bytes] long. */
    data class Screenshot(val file: File, val bytes: Long) : Attachment {
        override val line get() = "Screenshot"
    }
}

/** [pending] with its attachment of [attachment]'s kind replaced by it. */
fun replacing(pending: List<Attachment>, attachment: Attachment): List<Attachment> =
    pending.filterNot { it::class == attachment::class } + attachment

/** [pending] with its screen context replaced by [delivery]. */
fun withDelivery(pending: List<Attachment>, delivery: List<Attachment>): List<Attachment> =
    pending.filterNot { it is Attachment.ScreenText || it is Attachment.Screenshot } + delivery
