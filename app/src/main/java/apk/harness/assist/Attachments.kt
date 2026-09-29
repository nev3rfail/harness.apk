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

    /** A shared image encoded into [file], going as a block. */
    data class Image(val file: File, val mediaType: String, val bytes: Long, val name: String) : Attachment {
        override val line get() = "Image: $name"
    }

    /** A shared file copied into the inbox, named by path in the message. */
    data class Document(val file: File) : Attachment {
        override val line get() = "File: ${file.name}"
    }
}

/** [pending] with its attachment of [attachment]'s kind replaced by it. */
fun replacing(pending: List<Attachment>, attachment: Attachment): List<Attachment> =
    pending.filterNot { it::class == attachment::class } + attachment

