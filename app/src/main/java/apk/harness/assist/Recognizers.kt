package apk.harness.assist

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.service.voice.VoiceInteractionService
import android.speech.RecognitionService

/** Every speech recognizer on the device, forwarders included. */
fun recognizerCandidates(context: Context): List<RecognizerCandidate> =
    context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map {
        val info = it.serviceInfo
        RecognizerCandidate(info.packageName, info.name, (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
    }

fun RecognizerCandidate.component() = ComponentName(packageName, className)

/**
 * Whether the app is the device's assistant: the role from 29, and below it
 * whether [AssistantService] is the active voice interaction service.
 */
fun holdsAssistant(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return VoiceInteractionService.isActiveService(context, ComponentName(context, AssistantService::class.java))
    }
    val roles = context.getSystemService(RoleManager::class.java)
    return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
}

/** [asksMicForOtherApps] on this device, now. */
fun asksMicForOtherApps(context: Context): Boolean {
    return asksMicForOtherApps(
        api = Build.VERSION.SDK_INT,
        roleHeld = holdsAssistant(context),
        granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    )
}
