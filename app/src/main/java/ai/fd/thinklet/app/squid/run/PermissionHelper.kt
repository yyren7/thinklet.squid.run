package ai.fd.thinklet.app.squid.run

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限管理助手类
 * 用于请求和检查应用所需的权限
 */
class PermissionHelper(private val activity: Activity) {
    
    companion object {
        const val PERMISSION_REQUEST_CODE = 1001
        
        /**
         * 应用所需的权限列表
         */
        val REQUIRED_PERMISSIONS = listOfNotNull(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
                .takeIf { Build.VERSION.SDK_INT <= Build.VERSION_CODES.P }
        )
    }
    
    /**
     * 检查是否已获得所有必需权限
     */
    fun areAllPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 获取未授权的权限列表
     */
    fun getDeniedPermissions(): List<String> {
        return REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 请求所有必需的权限
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
     * 检查是否需要显示权限说明
     */
    fun shouldShowRequestPermissionRationale(): Boolean {
        return getDeniedPermissions().any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }
    
    /**
     * 获取权限的友好名称
     */
    fun getPermissionFriendlyName(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> "摄像头"
            Manifest.permission.RECORD_AUDIO -> "麦克风"
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "存储"
            else -> permission
        }
    }
    
    /**
     * 获取所有未授权权限的友好名称列表
     */
    fun getDeniedPermissionNames(): List<String> {
        return getDeniedPermissions().map { getPermissionFriendlyName(it) }
    }
}
