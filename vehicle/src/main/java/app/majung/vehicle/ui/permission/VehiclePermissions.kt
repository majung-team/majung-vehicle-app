package app.majung.vehicle.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import app.majung.vehicle.VehicleApplication
import app.majung.vehicle.service.PickupSharingService

internal class VehiclePermissions(
    private val activity: Activity,
    private val app: VehicleApplication,
    private val canInteract: () -> Boolean,
    private val isResumed: () -> Boolean,
    private val render: () -> Unit,
    private val onStartReset: () -> Unit,
) {
    var permissionRequestInFlight = false
    var firstUsePermissionPending = false
    private val permissionPreferences by lazy {
        activity.getSharedPreferences("location-permission", android.content.Context.MODE_PRIVATE)
    }
    var permissionAttempted = false
    var notificationPermissionAttempted = false

    fun restore(savedInstanceState: Bundle?) {
        firstUsePermissionPending =
            savedInstanceState?.getBoolean("firstUsePermissionPending") == true
        permissionRequestInFlight = firstUsePermissionPending
        permissionAttempted = permissionPreferences.getBoolean("requested", false)
        notificationPermissionAttempted =
            permissionPreferences.getBoolean("notification_requested", false)
    }

    fun requestSharingPermission() {
        if (
            !isResumed() ||
                !canInteract() ||
                !app.sharingNotice.accepted ||
                permissionRequestInFlight
        )
            return
        if (!PickupSharingService.hasLocationPermission(activity)) {
            if (
                permissionAttempted &&
                    !activity.shouldShowRequestPermissionRationale(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) &&
                    !activity.shouldShowRequestPermissionRationale(
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
            ) {
                activity.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${activity.packageName}"),
                    )
                )
                return
            }
            launchLocationPermission()
        } else {
            onStartReset()
            render()
        }
    }

    private fun isRunningInstrumentationTest(): Boolean =
        try {
            val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
            registry.getMethod("getInstrumentation").invoke(null) != null
        } catch (_: Throwable) {
            false
        }

    fun maybeRequestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT < 33 ||
                !isResumed() ||
                !canInteract() ||
                !app.sharingNotice.accepted ||
                permissionRequestInFlight ||
                notificationPermissionAttempted ||
                isRunningInstrumentationTest()
        )
            return
        if (
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionAttempted = true
            permissionPreferences.edit().putBoolean("notification_requested", true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 33)
        }
    }

    fun launchLocationPermission() {
        if (!isResumed() || !canInteract() || permissionRequestInFlight) return
        permissionAttempted = true
        permissionPreferences.edit().putBoolean("requested", true).apply()
        permissionRequestInFlight = true
        val permissions =
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermissionAttempted = true
                permissionPreferences.edit().putBoolean("notification_requested", true).apply()
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            }
        activity.requestPermissions(permissions, 32)
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == 32) {
            permissionRequestInFlight = false
            app.sharingMessage =
                if (PickupSharingService.hasLocationPermission(activity)) "공유 시작 준비 중"
                else "위치 권한이 꺼져 있어 차량 위치와 도착 시간을 공유하지 않아요"
            if (
                canInteract() &&
                    Build.VERSION.SDK_INT >= 33 &&
                    PickupSharingService.hasLocationPermission(activity) &&
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED &&
                    !notificationPermissionAttempted
            ) {
                notificationPermissionAttempted = true
                permissionPreferences.edit().putBoolean("notification_requested", true).apply()
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 33)
            } else {
                firstUsePermissionPending = false
            }
            onStartReset()
        } else if (requestCode == 33) {
            if (firstUsePermissionPending) permissionRequestInFlight = false
            firstUsePermissionPending = false
        }
        render()
    }
}
