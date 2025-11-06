package ai.fd.thinklet.app.squid.run

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Permission management helper class.
 * Used to request and check the permissions required by the application.
 */
class PermissionHelper(private val activity: Activity) {
    
    companion object {
        const val PERMISSION_REQUEST_CODE = 1001
        
        /**
         * List of permissions required by the application.
         */
        val REQUIRED_PERMISSIONS = listOfNotNull(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
                .takeIf { Build.VERSION.SDK_INT <= Build.VERSION_CODES.P },
            // Permissions required for iBeacon geofence
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            // Bluetooth permissions required for Android 12+ (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 
                Manifest.permission.BLUETOOTH_SCAN else null,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 
                Manifest.permission.BLUETOOTH_CONNECT else null
        )
    }
    
    /**
     * Check if all required permissions have been granted.
     */
    fun areAllPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get a list of denied permissions.
     */
    fun getDeniedPermissions(): List<String> {
        return REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Request all required permissions.
     */
    fun requestPermissions() {
        val deniedPermissions = getDeniedPermissions()
        if (deniedPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                deniedPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    /**
     * Check if it is necessary to show the permission rationale.
     */
    fun shouldShowRequestPermissionRationale(): Boolean {
        return getDeniedPermissions().any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }
    
    /**
     * Get the friendly name of a permission.
     */
    fun getPermissionFriendlyName(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> "Camera"
            Manifest.permission.RECORD_AUDIO -> "Microphone"
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Storage"
            Manifest.permission.READ_PHONE_STATE -> "Phone State (for Device ID)"
            Manifest.permission.ACCESS_FINE_LOCATION -> "Location (for iBeacon Geofence)"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Coarse Location"
            "android.permission.BLUETOOTH_SCAN" -> "Bluetooth Scan (for iBeacon)"
            "android.permission.BLUETOOTH_CONNECT" -> "Bluetooth Connect"
            else -> permission
        }
    }
    
    /**
     * Get a list of friendly names for all denied permissions.
     */
    fun getDeniedPermissionNames(): List<String> {
        return getDeniedPermissions().map { getPermissionFriendlyName(it) }
    }
}
