package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForwardTest {

    private fun candidate(pkg: String, system: Boolean, cls: String = "$pkg.Recognizer") = RecognizerCandidate(pkg, cls, system)

    @Test
    fun `a system recognizer is chosen over another one`() {
        val google = candidate("com.google.android.tts", system = true)
        assertEquals(google, forwardTarget(listOf(candidate("a.other", system = false), google)))
    }

    @Test
    fun `two system recognizers are ordered by package`() {
        assertEquals(candidate("a.sys", true), forwardTarget(listOf(candidate("b.sys", true), candidate("a.sys", true))))
    }

    @Test
    fun `this channel's forwarder and another channel's are never chosen`() {
        val own = RecognizerCandidate("dev.harness", FORWARDER_CLASS, system = false)
        val other = RecognizerCandidate("btl.harness", FORWARDER_CLASS, system = false)
        val real = candidate("z.voice", system = false)
        assertEquals(real, forwardTarget(listOf(own, other, real)))
    }

    @Test
    fun `only forwarders is none`() {
        assertNull(forwardTarget(listOf(RecognizerCandidate("dev.harness", FORWARDER_CLASS, false))))
    }

    @Test
    fun `no candidates is none`() {
        assertNull(forwardTarget(emptyList()))
    }

    @Test
    fun `the microphone is asked for other apps only on Android 11, as the assistant, without the grant`() {
        assertEquals(true, asksMicForOtherApps(api = 30, roleHeld = true, granted = false))
        assertEquals(false, asksMicForOtherApps(api = 30, roleHeld = true, granted = true))
        assertEquals(false, asksMicForOtherApps(api = 30, roleHeld = false, granted = false))
        assertEquals(false, asksMicForOtherApps(api = 31, roleHeld = true, granted = false))
    }

    @Test
    fun `the on-device recognizer is used from Android 12 where it is wanted and available`() {
        assertEquals(RecognizerKind.OnDevice, recognizerKind(api = 31, onDevice = true) { true })
        assertEquals(RecognizerKind.Default, recognizerKind(api = 35, onDevice = true) { false })
        assertEquals(RecognizerKind.Default, recognizerKind(api = 35, onDevice = false) { true })
    }

    @Test
    fun `Android 11 forwards whatever is wanted, and never asks about the on-device one`() {
        assertEquals(RecognizerKind.Forward, recognizerKind(api = 30, onDevice = true) { error("not on API 30") })
    }

    @Test
    fun `only an on-device listen lacking the language is retried, once`() {
        assertEquals(true, retriesOnline(onDevice = true, retried = false, noLanguage = true))
        assertEquals(false, retriesOnline(onDevice = true, retried = true, noLanguage = true))
        assertEquals(false, retriesOnline(onDevice = false, retried = false, noLanguage = true))
        assertEquals(false, retriesOnline(onDevice = true, retried = false, noLanguage = false))
    }
}
