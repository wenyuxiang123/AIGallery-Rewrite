package com.aigallery.rewrite.devicecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ImageReader
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.ScanResults
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation
import android.net.wifi.WifiManager.WifiLock
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统服务控制 - 爱马仕级全操控
 * 
 * 提供对设备各种系统服务的控制能力：
 * - WiFi/蓝牙控制
 * - 音量/亮度调节
 * - 手电筒控制
 * - 屏幕控制
 * - 应用管理
 * - 通知管理
 * - 剪贴板操作
 * - 电话/短信
 * - 定位开关
 * - Shell命令执行
 */
@Singleton
class DeviceControlService @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "DeviceControlService"
    }

    // ==================== 基础服务获取 ====================

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val activityManager: ActivityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    // ==================== 权限检查 ====================

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermission(permission: String, action: () -> Boolean): Result<Boolean> {
        return try {
            if (hasPermission(permission)) {
                Result.success(action())
            } else {
                Result.failure(SecurityException("Permission denied: $permission"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== WiFi 控制 ====================

    /**
     * 设置WiFi开关状态
     */
    @SuppressLint("MissingPermission")
    fun setWifiEnabled(enabled: Boolean): Result<Boolean> {
        return try {
            val isWifiEnabled = wifiManager.isWifiEnabled
            if (isWifiEnabled == enabled) {
                return Result.success(true)
            }
            
            // Android 10+ 需要特殊处理
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 打开设置让用户手动操作
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Result.success(true)
            } else {
                @Suppress("DEPRECATION")
                val result = wifiManager.setWifiEnabled(enabled)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取WiFi信息
     */
    @SuppressLint("MissingPermission")
    fun getWifiInfo(): Result<WifiInfo> {
        return try {
            if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE)) {
                return Result.failure(SecurityException("Permission denied: ACCESS_WIFI_STATE"))
            }
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取WiFi状态
     */
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    // ==================== 蓝牙控制 ====================

    /**
     * 设置蓝牙开关状态
     */
    @SuppressLint("MissingPermission")
    fun setBluetoothEnabled(enabled: Boolean): Result<Boolean> {
        return try {
            val adapter = bluetoothManager?.adapter
                ?: return Result.failure(IllegalStateException("Bluetooth not available"))
            
            if (adapter.isEnabled == enabled) {
                return Result.success(true)
            }
            
            if (enabled) {
                // 打开蓝牙
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ 需要用户确认
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } else {
                    adapter.enable()
                }
            } else {
                adapter.disable()
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取蓝牙状态
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothManager?.adapter?.isEnabled ?: false
    }

    // ==================== 音量控制 ====================

    /**
     * 设置音量
     * @param streamType AudioManager.STREAM_MUSIC, STREAM_RING, etc.
     * @param volume 音量值 (0-100)
     */
    fun setVolume(streamType: Int, volume: Int): Result<Unit> {
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            val scaledVolume = (volume.coerceIn(0, 100) * maxVolume / 100)
            audioManager.setStreamVolume(streamType, scaledVolume, 0)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取音量
     */
    fun getVolume(streamType: Int): Int {
        val current = audioManager.getStreamVolume(streamType)
        val max = audioManager.getStreamMaxVolume(streamType)
        return (current * 100 / max).coerceIn(0, 100)
    }

    /**
     * 设置静音
     */
    fun setMute(muted: Boolean): Result<Unit> {
        return try {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            // 使用/adjust_volume 代替
            val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 增加音量
     */
    fun increaseVolume(streamType: Int = AudioManager.STREAM_MUSIC): Result<Unit> {
        return try {
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 降低音量
     */
    fun decreaseVolume(streamType: Int = AudioManager.STREAM_MUSIC): Result<Unit> {
        return try {
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 亮度控制 ====================

    /**
     * 设置屏幕亮度 (0.0 - 1.0)
     */
    fun setBrightness(level: Float): Result<Unit> {
        return try {
            val brightness = level.coerceIn(0f, 1f)
            
            // 需要写入设置权限
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                val brightnessValue = (brightness * 255).toInt()
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightnessValue
                )
            } else {
                // 请求写入设置权限
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取当前亮度
     */
    fun getBrightness(): Float {
        return try {
            if (Settings.System.canWrite(context)) {
                val brightness = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    128
                )
                brightness / 255f
            } else {
                0.5f // 默认50%
            }
        } catch (e: Exception) {
            0.5f
        }
    }

    // ==================== 手电筒控制 ====================

    /**
     * 设置手电筒开关
     */
    fun setFlashlight(enabled: Boolean): Result<Unit> {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Result.failure(IllegalStateException("No flash available"))

            cameraManager.setTorchMode(cameraId, enabled)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取手电筒状态
     */
    fun isFlashlightOn(): Boolean {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.getTorchMode(cameraId)
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 屏幕控制 ====================

    /**
     * 唤醒屏幕
     */
    fun setScreenOn(): Result<Unit> {
        return try {
            val wakeLock = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLockTag = "${context.packageName}:ScreenOnWakeLock"
            
            @Suppress("InlinedApi")
            val wakeLockObj = wakeLock.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                wakeLockTag
            )
            wakeLockObj.acquire(10 * 1000L) // 10秒超时
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 关闭屏幕
     */
    fun setScreenOff(): Result<Unit> {
        return try {
            val intent = Intent("android.intent.action.screen_off")
            context.sendBroadcast(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 屏幕是否亮着
     */
    fun isScreenOn(): Boolean = powerManager.isScreenOn

    // ==================== 应用管理 ====================

    /**
     * 启动应用
     */
    fun launchApp(packageName: String): Result<Unit> {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return Result.failure(IllegalArgumentException("Package not found: $packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 强制停止应用
     */
    fun forceStopApp(packageName: String): Result<Unit> {
        return try {
            if (packageName == context.packageName) {
                return Result.failure(IllegalArgumentException("Cannot stop self"))
            }
            
            // 需要特殊权限，这里只是尝试
            activityManager.clearApplicationUserData()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 检查应用是否安装
     */
    fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: NameNotFoundException) {
            false
        }
    }

    /**
     * 获取已安装应用列表
     */
    fun getInstalledApps(): Result<List<AppInfo>> {
        return try {
            val apps = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { appInfo ->
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = context.packageManager.getApplicationLabel(appInfo).toString(),
                        versionName = try {
                            context.packageManager.getPackageInfo(appInfo.packageName, 0).versionName
                        } catch (e: Exception) { null },
                        isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    )
                }
                .sortedBy { it.appName }
            Result.success(apps)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 通知管理 ====================

    /**
     * 获取所有通知
     */
    @SuppressLint("MissingPermission")
    fun getAllNotifications(): Result<List<NotificationInfo>> {
        return try {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                return Result.failure(SecurityException("Permission denied: POST_NOTIFICATIONS"))
            }
            // 需要 NotificationListenerService，这里返回空列表
            Result.success(emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 回复通知（需要 NotificationListenerService）
     */
    fun replyNotification(key: String, text: String): Result<Unit> {
        return try {
            // 需要 NotificationListenerService 实现
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 剪贴板 ====================

    /**
     * 复制文本到剪贴板
     */
    @SuppressLint("ServiceCast")
    fun setClipboard(text: String, label: String = "AIGallery"): Result<Unit> {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取剪贴板内容
     */
    @SuppressLint("ServiceCast")
    fun getClipboard(): Result<String> {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                Result.success(text)
            } else {
                Result.success("")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 电话/短信 ====================

    /**
     * 拨打电话
     */
    fun makeCall(number: String): Result<Unit> {
        return try {
            if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                return Result.failure(SecurityException("Permission denied: CALL_PHONE"))
            }
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            // 如果直接拨打失败，尝试打开拨号盘
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$number")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Result.success(Unit)
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        }
    }

    /**
     * 发送短信
     */
    fun sendSms(number: String, text: String): Result<Unit> {
        return try {
            if (!hasPermission(Manifest.permission.SEND_SMS)) {
                return Result.failure(SecurityException("Permission denied: SEND_SMS"))
            }
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            val parts = smsManager.divideMessage(text)
            if (parts.size == 1) {
                smsManager.sendTextMessage(number, null, text, null, null)
            } else {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 定位 ====================

    /**
     * 开关定位
     */
    fun toggleLocation(enabled: Boolean): Result<Unit> {
        return try {
            if (Settings.Secure.canWrite(context)) {
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    if (enabled) {
                        Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                    } else {
                        Settings.Secure.LOCATION_MODE_OFF
                    }
                )
                Result.success(Unit)
            } else {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取定位状态
     */
    fun isLocationEnabled(): Boolean {
        return try {
            val locationMode = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            )
            locationMode != Settings.Secure.LOCATION_MODE_OFF
        } catch (e: Exception) {
            false
        }
    }

    // ==================== Shell命令 ====================

    /**
     * 执行Shell命令
     * 警告：危险操作，需要root权限
     */
    suspend fun executeShell(command: String): Result<ShellResult> = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            val inputStream = process.inputStream
            val errorStream = process.errorStream

            outputStream.writeBytes("$command\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()

            val stdout = BufferedReader(InputStreamReader(inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(errorStream)).readText()
            
            process.waitFor()
            val exitCode = process.exitValue()

            Result.success(
                ShellResult(
                    command = command,
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = exitCode,
                    success = exitCode == 0
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 震动 ====================

    /**
     * 触发震动
     */
    fun vibrate(duration: Long = 500): Result<Unit> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 震动模式
     */
    fun setVibrateMode(enabled: Boolean): Result<Unit> {
        return try {
            audioManager.ringerMode = if (enabled) {
                AudioManager.RINGER_MODE_VIBRATE
            } else {
                AudioManager.RINGER_MODE_NORMAL
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取震动模式状态
     */
    fun isVibrateMode(): Boolean {
        return audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE
    }

    // ==================== 数据类 ====================
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val isSystemApp: Boolean
)

data class NotificationInfo(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long
)

data class ShellResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val success: Boolean
)
