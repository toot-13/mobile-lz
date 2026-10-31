package com.dylr.recorder.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.dylr.recorder.ui.theme.DouyinLiveRecTheme

class MainActivity : ComponentActivity() {

    // 只记录结果用于排查，拒绝也不影响录制主流程
    private var anyPermissionDenied = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            anyPermissionDenied = result.values.any { granted -> !granted }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Locator 已在 App.onCreate 里初始化，这里不再重复 init（避免重建 Recorder 丢掉状态）
        requestRuntimePermissions()
        val vm: MainViewModel by viewModels()
        setContent {
            DouyinLiveRecTheme {
                MainScreen(vm)
            }
        }
    }

    /**
     * 按版本组装一次性申请的权限：
     * - API 33+：POST_NOTIFICATIONS（前台录制通知）
     * - API 26~28：WRITE_EXTERNAL_STORAGE（MediaStoreSaver 直写 Movies/DouyinLiveRec 兜底分支的前置条件）
     * 已授权的跳过；没有需要申请的就不弹窗；任何异常都吞掉，不阻塞 UI、不二次弹窗。
     */
    private fun requestRuntimePermissions() {
        try {
            val missing = ArrayList<String>(2)
            if (Build.VERSION.SDK_INT >= 33 && !isGranted(POST_NOTIFICATIONS)) {
                missing.add(POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT in 26..28 && !isGranted(WRITE_EXTERNAL_STORAGE)) {
                missing.add(WRITE_EXTERNAL_STORAGE)
            }
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
            }
        } catch (t: Throwable) {
            // 部分定制系统上权限请求可能抛异常，这里吞掉，不阻塞主界面
        }
    }

    private fun isGranted(permission: String): Boolean =
        runCatching { checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private companion object {
        // 用字面量常量，不依赖只有高 compileSdk 才带的 Manifest.permission.* 常量
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
    }
}
