package com.focusguard.app.boot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusguard.app.FocusGuardApp
import com.focusguard.app.data.LockState
import com.focusguard.app.data.Settings
import com.focusguard.app.enforce.LockScreenActivity
import com.focusguard.app.service.GuardWatchdogWorker
import com.focusguard.app.service.LockGuardService
import com.focusguard.app.usage.UsageRuleStore

/**
 * 开机 / 应用更新自启动接收器。
 *
 * 触发场景：
 * - BOOT_COMPLETED、QUICKBOOT_POWERON（各厂商快速启动）
 * - MY_PACKAGE_REPLACED（应用被覆盖安装升级后）
 *
 * 做三件事：
 * 1. 启动 [LockGuardService]——防破解的主防线，不依赖无障碍
 * 2. 注册 [GuardWatchdogWorker]——最后一道兜底
 * 3. 锁机状态仍在 → 直接拉起锁机页（重启手机也无法绕过锁机）
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val TRIGGER_ACTIONS = setOf(
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            "android.intent.action.USER_SWITCHED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in TRIGGER_ACTIONS) return

        Log.d(TAG, "收到启动广播：$action")
        val app = context.applicationContext

        try {
            val lockState = LockState(app)
            val usageRuleStore = UsageRuleStore(app)

            // 1. 有锁机或有硬封锁规则 → 启动守护服务
            val hasBlockRule = usageRuleStore.allRules().any { it.hardBlockMinutes != null }
            if (lockState.isLocked || hasBlockRule) {
                Log.d(TAG, "存在锁机或封锁规则，启动锁机守护服务")
                LockGuardService.start(app)
            }

            // 2. 无论如何都注册看门狗与 5 秒自愈闹钟（秒级拉活防破解）
            GuardWatchdogWorker.schedule(app)
            try {
                com.focusguard.app.service.LockGuardAlarm.schedule(
                    app,
                    com.focusguard.app.service.LockGuardAlarm.RECOVERY_INTERVAL_MS
                )
            } catch (e: Exception) {
                Log.w(TAG, "注册开机自愈闹钟失败：${e.message}")
            }

            // 2.5 待办到期提醒重排：重启后 AlarmManager 全部清空，必须重建
            try {
                com.focusguard.app.service.MemoReminder.sync(app)
            } catch (e: Exception) {
                Log.w(TAG, "待办提醒重排失败：${e.message}")
            }

            // 3. 锁机状态仍在 → 立即恢复锁机页/全屏悬浮窗
            if (lockState.isLocked && lockState.shouldBlockNow) {
                Log.d(TAG, "开机后锁机状态仍有效，恢复锁机页与悬浮窗")
                LockScreenActivity.show(app)
            }

            // 4. 守护服务（AI 检测）开机前在运行 → 恢复
            val settings = Settings(app)
            if (settings.serviceRunning) {
                // 先以"无画面模式"把守护拉起来：前台应用分类与媒体播放判定
                // 完全不依赖屏幕共享，所以开机后守护能立刻继续生效；
                // 若无障碍服务随后连上，画面通道会自动接上（无需用户操作）。
                com.focusguard.app.service.MonitorService.startWithoutScreenShare(app)
                // 一条画面通道都没有时，才提醒用户去开无障碍/重新授权录屏
                if (!com.focusguard.app.access.GuardAccessibilityService.screenshotCapable) {
                    notifyReopen(app)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "启动广播处理失败：${e.message}")
        }
    }

    /**
     * 提醒重新开启 AI 守护的画面识别。
     *
     * 守护本身已经以"无画面模式"自动恢复（见调用处），这里只是提示
     * 用户可以补上画面通道：开无障碍，或重新确认屏幕共享授权。
     */
    private fun notifyReopen(context: Context) {
        try {
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                1,
                Intent(context, com.focusguard.app.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = android.app.Notification.Builder(context, FocusGuardApp.CHANNEL_ID)
                .setSmallIcon(com.focusguard.app.R.drawable.ic_shield)
                .setContentTitle("守护已自动恢复（暂无画面通道）")
                .setContentText("点此开启无障碍服务即可恢复画面识别（守护与锁机已在运行）")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1001, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发送开机提醒失败：${e.message}")
        }
    }
}
