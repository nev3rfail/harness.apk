package apk.harness.assist

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognitionService

/** Every speech recognizer on the device, forwarders included. */
fun recognizerCandidates(context: Context): List<RecognizerCandidate> =
    context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map {
        val info = it.serviceInfo
        RecognizerCandidate(info.packageName, info.name, (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
    }

fun RecognizerCandidate.component() = ComponentName(packageName, className)

/** [asksMicForOtherApps] on this device, now. */
fun asksMicForOtherApps(context: Context): Boolean {
    val roles = context.getSystemService(RoleManager::class.java)
    return asksMicForOtherApps(
        api = Build.VERSION.SDK_INT,
        roleHeld = roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT),
        granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    )
}
