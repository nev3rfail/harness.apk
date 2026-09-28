package apk.harness.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.speech.RecognitionService

/** Every speech recognizer on the device, forwarders included. */
fun recognizerCandidates(context: Context): List<RecognizerCandidate> =
    context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map {
        val info = it.serviceInfo
        RecognizerCandidate(info.packageName, info.name, (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
    }

fun RecognizerCandidate.component() = ComponentName(packageName, className)
