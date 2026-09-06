package com.timelapse.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.timelapse.camera.databinding.ActivityMainBinding
import com.timelapse.camera.ui.gallery.GalleryFragment
import com.timelapse.camera.ui.preview.PreviewFragment
import com.timelapse.camera.ui.settings.SettingsFragment
import com.timelapse.camera.ui.status.StatusFragment
import com.timelapse.camera.service.WatchdogService

/**
 * 主 Activity —— 底部导航 + 4 个 Fragment 切换。
 *
 * 架构说明：
 * - 单一 Activity 架构，所有页面都是 Fragment
 * - BottomNavigationView 驱动 Fragment 切换
 * - 默认显示「状态」Tab（用户最常看的）
 * - lazy 初始化 Fragment 的设计决策：每次配置变更（屏幕旋转等）系统会重建
 *   MainActivity，但不会重新调用 newInstance()；持有 lazy 引用确保同一 Activity
 *   实例内始终使用相同的 Fragment 对象，避免重复创建导致的状态丢失。
 *   教学要点：对比 hide/show vs replace 的 trade-off。
 *
 * 教学要点：
 * - 底部导航配合 Fragment 的标准模式
 * - 单一 Activity 多 Fragment 的架构思路
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 
     * lazy 初始化各 Tab 的 Fragment。
     * 使用 lazy 而非每次 newInstance() 的原因：
     * 1. 系统配置变更时（如屏幕旋转），Activity 会被销毁重建，但 Fragment 由
     *    FragmentManager 管理，不依赖 Activity 重建；lazy 引用保证同一进程内
     *    Fragment 实例唯一，避免重复创建。
     * 2. 代码更简洁，避免在每次导航时重新实例化。
     */
    private val statusFragment by lazy { StatusFragment.newInstance() }
    private val previewFragment by lazy { PreviewFragment.newInstance() }
    private val galleryFragment by lazy { GalleryFragment.newInstance() }
    private val settingsFragment by lazy { SettingsFragment.newInstance() }

    /** Android 13+ 通知权限：前台服务倒计时通知依赖它，未授权则通知不显示 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果无需处理：拒绝仅影响通知可见性，不影响拍摄功能 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 13+ 运行时通知权限（前台服务的倒计时通知依赖）
        requestNotificationPermissionIfNeeded()

        // 启动守护服务（独立进程，检测主服务并负责重启）
        startService(Intent(this, WatchdogService::class.java))

        // 默认显示状态页
        if (savedInstanceState == null) {
            switchFragment(statusFragment)
            binding.bottomNav.selectedItemId = R.id.nav_status
        }

        // 底部导航点击事件
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_status -> switchFragment(statusFragment)
                R.id.nav_preview -> switchFragment(previewFragment)
                R.id.nav_gallery -> switchFragment(galleryFragment)
                R.id.nav_settings -> switchFragment(settingsFragment)
            }
            true
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 切换 Fragment。
     * 使用 replace 而不是 hide/show：
     * - 代码更简洁，教学更清晰
     * - 切换时 Fragment 重建，保证每次进入页面数据都是最新的
     * - 代价是切换时会重建视图，但对这个 App 来说完全可接受
     */
    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
