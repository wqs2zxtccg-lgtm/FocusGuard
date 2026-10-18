package com.focusguard.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.focusguard.app.FocusGuardApp
import com.focusguard.app.MainActivity
import com.focusguard.app.R
import com.focusguard.app.ai.AiClient
import com.focusguard.app.capture.AccessibilityFrameSource
import com.focusguard.app.capture.FrameSource
import com.focusguard.app.capture.MediaProjectionFrameSource
import com.focusguard.app.data.DetectionLog
import com.focusguard.app.data.LockState
import com.focusguard.app.data.LogStore
import com.focusguard.app.data.Settings
import com.focusguard.app.detection.AppCategory
import com.focusguard.app.detection.AppCategoryStore
import com.focusguard.app.detection.AppClassifier
import com.focusguard.app.detection.DetectionOutcome
import com.focusguard.app.detection.DetectionPipeline
import com.focusguard.app.detection.DetectionSource
import com.focusguard.app.detection.MediaPlaybackDetector
import com.focusguard.app.enforce.AppBlockActivity
import com.focusguard.app.enforce.Enforcer
import com.focusguard.app.token.AdaptiveScheduler
import com.focusguard.app.token.DecisionCache
import com.focusguard.app.token.TokenBudget
import com.focusguard.app.usage.UsageRuleStore
import com.focusguard.app.usage.UsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台守护服务：周期性执行三级检测，并在判定为娱乐时执法。
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val ACTION_START = "com.focusguard.app.START"
        private const val ACTION_STOP = "com.focusguard.app.STOP"
        private const val ACTION_TEST = "com.focusguard.app.TEST"
        private const val ACTION_RESURRECT = "com.focusguard.app.RESURRECT"

        /** 不申请屏幕共享，直接用无障碍截图 + 事件层判定启动守护。 */
        private const val ACTION_START_TEXT_ONLY = "com.focusguard.app.START_TEXT_ONLY"

        /** 使用时长闸门的采样间隔（毫秒）。比 AI 检测间隔密得多，才能及时拦截。 */
        const val USAGE_TICK_MS = 15_000L

        /** 服务是否正在运行，UI 可据此显示真实状态。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 最近一次检测结果，供"测试识别"等界面读取。 */
        @Volatile
        var lastOutcome: DetectionOutcome? = null
            private set

        /**
         * 巡检循环心跳时间戳（每 15 秒更新一次）。
         *
         * 用于排查"守护显示开着但不出日志"：若 isRunning=true 却
         * lastTickAt 长时间不变，说明循环已死（协程被取消/异常吞掉），
         * 而不是 AI 接口的问题。主页会显示这个状态。
         */
        @Volatile
        var lastTickAt: Long = 0L
            private set

        /** 最近一次真正执行 AI 检测的时间戳。 */
        @Volatile
        var lastDetectionAt: Long = 0L
            private set

        /** 巡检循环是否活着（心跳在 3 个 tick 周期内）。 */
        fun isLoopAlive(): Boolean =
            lastTickAt > 0 && System.currentTimeMillis() - lastTickAt < USAGE_TICK_MS * 3

        /** 智能调度：下次检测间隔（秒）。0=未启用/未计算。 */
        @Volatile
        var smartIntervalSeconds: Long = 0L

        /** 智能调度：当前风险分（0-100）。 */
        @Volatile
        var smartRiskPercent: Int = 0

        /** 智能调度：决策依据文本（供 UI 展示）。 */
        @Volatile
        var smartReason: String = ""

        /**
         * 当前生效的画面采集通道名。
         * 空串表示"没有任何画面通道"，此时判定完全依赖事件层信号
         * （前台应用 + 屏幕文字 + 媒体播放状态），守护仍然有效。
         */
        @Volatile
        var captureChannel: String = ""

        /** 采集通道文案，供 UI/通知展示。 */
        fun captureChannelText(): String = captureChannel.ifBlank { "无画面（事件层判定）" }

        /** 诊断文本：给用户看的守护健康状态。 */
        fun healthText(): String {
            if (!isRunning) return "守护未运行"
            val now = System.currentTimeMillis()
            val tickAgo = if (lastTickAt == 0L) -1 else ((now - lastTickAt) / 1000)
            val detectAgo = if (lastDetectionAt == 0L) -1 else ((now - lastDetectionAt) / 1000)
            return buildString {
                append("巡检心跳：")
                append(if (tickAgo < 0) "尚未开始" else "${tickAgo}s 前")
                append(" · AI 检测：")
                append(if (detectAgo < 0) "尚未执行" else "${detectAgo}s 前")
                append("\n画面通道：")
                append(captureChannelText())
                if (!isLoopAlive()) append(" ⚠️ 循环已停止")
                // 智能调度状态：下次检测倒计时 + 风险分
                if (smartIntervalSeconds > 0) {
                    append("\n智能调度：下次 ${smartIntervalSeconds}s · 风险 ${smartRiskPercent}%")
                    if (smartReason.isNotBlank()) {
                        append("\n（${smartReason}）")
                    }
                }
            }
        }

        fun startService(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MonitorService::class.java).apply {
                action = ACTION_START
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            launch(context, intent)
        }

        /**
         * 启动守护，但**不申请屏幕共享授权**。
         *
         * 适用场景：无障碍服务已开启（API 30+ 可截图）。
         * 这样不再弹系统授权框，也不会因为息屏被系统"结束共享"；
         * 检测通道由无障碍截图 + 屏幕文字 + 应用分类 + 媒体播放状态组成。
         */
        fun startWithoutScreenShare(context: Context) {
            launch(
                context,
                Intent(context, MonitorService::class.java).apply {
                    action = ACTION_START_TEXT_ONLY
                }
            )
        }

        private fun launch(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).apply {
                action = ACTION_STOP
            })
        }

        /** 立即执行一次检测，不等待下一个周期。 */
        fun requestImmediateCheck(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).apply {
                action = ACTION_TEST
            })
        }

        /**
         * 复活检测循环（自愈用）。
         *
         * 主页检测到「守护开着但循环已停」（心跳停止更新）时调用：
         * 若 MediaProjection 仍有效则直接重启循环；投影已失效则走
         * 重建分支发"点击恢复"通知。
         */
        fun resurrect(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).apply {
                action = ACTION_RESURRECT
            })
        }
    }

    private lateinit var settings: Settings
    private lateinit var logStore: LogStore
    private lateinit var lockState: LockState
    private lateinit var aiClient: AiClient
    private lateinit var enforcer: Enforcer
    private lateinit var categoryStore: AppCategoryStore
    private lateinit var tokenBudget: TokenBudget
    private lateinit var decisionCache: DecisionCache
    private lateinit var usageRuleStore: UsageRuleStore
    private lateinit var usageTracker: UsageTracker
    private var scheduler: AdaptiveScheduler? = null

    /** 智能调度器（风险驱动的秒级动态间隔）。 */
    private var smartScheduler: com.focusguard.app.token.SmartScheduler? = null

    /** 上次检测是否失败（智能调度的退避依据）。 */
    private var lastDetectionWasError = false

    /** 屏幕共享通道（可能从未建立，也可能中途被系统回收）。 */
    private var mediaProjection: MediaProjection? = null
    private var mediaFrameSource: FrameSource? = null

    /** 无障碍截图通道：常驻。无障碍服务被回收时它会自动变为不可用。 */
    private val accessibilityFrameSource = AccessibilityFrameSource()

    private var pipeline: DetectionPipeline? = null

    /** 上次巡检时屏幕是否亮着，用于识别"刚亮屏"并立刻补一次检测。 */
    private var lastScreenInteractive = true

    /** 是否处于「使用时长触发」的加密检测模式。 */
    private var usageTriggeredDetection = false

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null
    private var consecutiveViolations = 0

    /** 提醒→锁机 宽限期任务（用户切回学习可免锁）。 */
    private var gracePeriodJob: Job? = null

    /**
     * MediaProjection 被系统或用户回收时的处理。
     *
     * 关键改动（本文件最重要的修复）：
     * **投影失效不再等于守护结束**。
     *
     * 旧实现在这里直接 stopMonitoring()，于是"手机一息屏 → 系统结束屏幕共享
     * → 整个守护停摆、必须手动重新授权"——这正是本次要解决的问题。
     * 现在只断开这条采集通道，巡检循环继续跑，自动退回到
     * 无障碍截图 / 屏幕文字 / 应用分类 / 媒体播放状态这些不依赖投影的信号。
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection 已被系统停止（多为息屏/锁屏或被回收）")
            detachProjection("屏幕共享已结束")
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        logStore = LogStore(this)
        lockState = LockState(this)
        aiClient = AiClient()
        enforcer = Enforcer(this)
        categoryStore = AppCategoryStore(this)
        tokenBudget = TokenBudget(this).apply { dailyCallLimit = settings.dailyCallLimit }
        decisionCache = DecisionCache(this)
        usageRuleStore = UsageRuleStore(this)
        usageTracker = UsageTracker(usageRuleStore)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra("resultCode", 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>("data")
                if (data != null) {
                    startMonitoring(resultCode, data)
                } else {
                    // 没有授权数据也不再自尽：退回不依赖投影的守护模式
                    Log.w(TAG, "缺少屏幕共享授权数据，改为无画面模式启动")
                    startTextOnlyMonitoring("缺少屏幕共享授权数据")
                }
            }
            ACTION_START_TEXT_ONLY -> startTextOnlyMonitoring("以无屏幕共享模式启动")
            ACTION_STOP -> stopMonitoring()
            ACTION_TEST -> serviceScope.launch { performDetection(isManualTest = true) }
            // 自愈：循环意外停止 → 无条件重启（不再要求投影仍然有效）
            ACTION_RESURRECT -> {
                Log.d(TAG, "收到复活指令（循环是否存活=${monitorJob?.isActive == true}）")
                ensurePipeline()
                if (monitorJob?.isActive != true) {
                    startMonitoringLoop()
                    Log.d(TAG, "检测循环已复活")
                }
            }
            // 系统在资源紧张时杀掉服务后重建（START_STICKY）：
            // 屏幕共享授权无法自动重放，但守护不该因此失效——
            // 只要用户之前开着守护，就用事件层判定继续跑。
            null -> {
                if (settings.serviceRunning) {
                    Log.w(TAG, "服务被系统重建（无授权数据），以无画面模式继续守护")
                    startTextOnlyMonitoring("服务被系统重建")
                } else {
                    Log.w(TAG, "服务被系统重建且用户并未开启守护，直接结束")
                    startForegroundCompat(buildNotification(null))
                    stopSelf()
                }
            }
        }
        // START_STICKY：服务被杀后系统会重建。
        // 重建后虽无 MediaProjection 无法检测，但前台服务身份保持，
        // 用户重新点击"开始守护"即可恢复完整功能。
        return START_STICKY
    }

    /**
     * 既没有屏幕共享、也没有无障碍截图时发通知。
     *
     * 注意语义变化：这**不是**"守护已中断"。守护仍在跑，
     * 只是暂时看不到画面（仅靠应用分类 + 媒体播放状态判定）。
     */
    private fun notifyNeedReauth() {
        try {
            val pendingIntent = PendingIntent.getActivity(
                this,
                5,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, FocusGuardApp.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("检测通道受限")
                .setContentText("守护仍在运行，但暂时取不到画面。开启无障碍服务可恢复画面识别，点此进入应用")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1004, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发送恢复提醒失败：${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring(resultCode: Int, data: Intent) {
        // 先进入前台：Android 14+ 要求前台服务（mediaProjection 类型）先就位，
        // 再取投影授权，顺序与旧实现保持一致（这个顺序是验证过可用的）。
        startForegroundCompat(buildNotification(null), projectionType = true)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "获取 MediaProjection 失败", e)
            null
        }

        if (projection == null) {
            // 授权数据无效也不结束守护：退回到不依赖画面的判定
            // （startTextOnlyMonitoring 会把前台服务类型换回 specialUse）
            Log.w(TAG, "屏幕共享授权无效，转为无画面模式")
            startTextOnlyMonitoring("屏幕共享授权无效")
            return
        }

        // Android 14 起必须在 createVirtualDisplay 之前注册回调
        projection.registerCallback(projectionCallback, null)
        mediaProjection = projection
        mediaFrameSource = MediaProjectionFrameSource(this, projection)

        settings.serviceRunning = true
        isRunning = true
        consecutiveViolations = 0
        scheduler = AdaptiveScheduler(settings.intervalMinutes)
        smartScheduler = com.focusguard.app.token.SmartScheduler(settings.intervalMinutes)

        ensurePipeline()
        updateCaptureChannel()
        startMonitoringLoop()
        Log.d(TAG, "守护已启动，采集通道 ${captureChannelText()}，间隔 ${settings.intervalMinutes} 分钟")
    }

    /**
     * 以「无画面模式」启动或续跑守护。
     *
     * 完全不依赖 MediaProjection：判定来自无障碍截图（若可用）、屏幕文字、
     * 前台应用分类与媒体播放状态。这正是"屏幕共享结束后守护不再失效"的落点。
     */
    private fun startTextOnlyMonitoring(reason: String) {
        Log.d(TAG, "进入无画面守护模式：$reason")

        if (settings.serviceRunning) {
            // 已经在守护中，这只是通道降级，不要重复初始化调度器
            startForegroundCompat(buildNotification(lastOutcome))
        } else {
            startForegroundCompat(buildNotification(null))
        }

        settings.serviceRunning = true
        isRunning = true
        consecutiveViolations = 0
        if (scheduler == null) scheduler = AdaptiveScheduler(settings.intervalMinutes)
        if (smartScheduler == null) {
            smartScheduler = com.focusguard.app.token.SmartScheduler(settings.intervalMinutes)
        }

        ensurePipeline()
        updateCaptureChannel()
        if (monitorJob?.isActive != true) startMonitoringLoop()
        Log.d(TAG, "无画面守护已就绪，采集通道 ${captureChannelText()}")

        // 连无障碍截图也没有时，提示用户开启无障碍，否则只能靠应用分类与媒体播放
        if (currentFrameSource() == null) notifyNeedReauth()
    }

    /** 懒创建检测流水线。画面通道用 provider 注入，投影随时可断可接。 */
    private fun ensurePipeline(): DetectionPipeline {
        pipeline?.let { return it }
        val created = DetectionPipeline(
            context = this,
            settings = settings,
            aiClient = aiClient,
            frameProvider = { currentFrameSource() },
            tokenBudget = tokenBudget,
            decisionCache = decisionCache,
            categoryStore = categoryStore
        )
        pipeline = created
        return created
    }

    /** 当前可用的画面通道：屏幕共享优先（信息最全），其次无障碍截图。 */
    private fun currentFrameSource(): FrameSource? {
        mediaFrameSource?.let { if (it.available) return it }
        return accessibilityFrameSource.takeIf { it.available }
    }

    /** 刷新采集通道状态，供 UI 与通知展示。 */
    private fun updateCaptureChannel() {
        captureChannel = currentFrameSource()?.name.orEmpty()
    }

    /**
     * 断开屏幕共享通道，但**保持守护运行**。
     *
     * 旧实现遇到投影失效会直接 stopMonitoring()，"息屏→共享结束→守护停摆"
     * 就是这么来的。现在只释放这一条通道，巡检循环继续，
     * 判定自动退回到无障碍截图 / 文字 / 应用分类 / 媒体播放。
     */
    private fun detachProjection(reason: String) {
        val hadProjection = mediaProjection != null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            mediaFrameSource?.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放屏幕共享通道失败：${e.message}")
        }
        // 刻意不在这里调 mediaProjection.stop()：
        // 本函数会从 MediaProjection.Callback.onStop() 内部被调用，
        // 在回调里再 stop() 有可能与框架的投影锁互相等待（已知死锁形态）。
        // 系统既然已经回调 onStop，说明投影本身已经停了，无需重复停止。
        mediaFrameSource = null
        mediaProjection = null
        updateCaptureChannel()

        if (!hadProjection) return

        // 降级前台服务类型：mediaProjection 类型要求当下持有有效授权
        try {
            startForegroundCompat(buildNotification(lastOutcome))
        } catch (e: Exception) {
            Log.w(TAG, "降级前台服务类型失败：${e.message}")
        }

        // 巡检循环必须还活着：投影断了只是少一只眼睛
        if (monitorJob?.isActive != true) {
            ensurePipeline()
            startMonitoringLoop()
        }

        if (currentFrameSource() == null) {
            try {
                AlertNotifier.alert(
                    this,
                    "已切换为无画面守护",
                    "$reason。守护仍在运行，将继续按应用分类与媒体播放状态检测；" +
                        "开启无障碍服务可恢复画面识别",
                    id = 1007
                )
            } catch (e: Exception) {
                Log.w(TAG, "发送通道切换提醒失败：${e.message}")
            }
        }
    }

    /**
     * 启动前台服务并按当前是否持有屏幕共享选择前台服务类型。
     *
     * Android 14+ 用 mediaProjection 类型启动前台服务时，系统会校验 App
     * 当下确实持有有效投影授权；无画面模式必须换成 specialUse，
     * 否则会直接抛 SecurityException 把服务打挂。
     */
    private fun startForegroundCompat(
        notification: Notification,
        projectionType: Boolean = mediaProjection != null
    ) {
        val id = FocusGuardApp.NOTIFICATION_ID
        val wantsProjection = projectionType

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val preferred = if (wantsProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            try {
                startForeground(id, notification, preferred)
                return
            } catch (e: Exception) {
                Log.w(TAG, "以前台服务类型 $preferred 启动失败：${e.message}")
            }
            // 兜底：按无画面类型再试一次，保证通知栏的守护标记一定挂上
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && wantsProjection) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(id, notification)
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            mediaFrameSource?.release()
        } catch (e: Exception) {
            Log.w(TAG, "关闭屏幕共享通道失败：${e.message}")
        }
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止投影失败（已停止则忽略）：${e.message}")
        }

        mediaFrameSource = null
        mediaProjection = null
        pipeline = null
        captureChannel = ""

        // 状态复位必须在 try 之外：
        // 此前 mediaProjection.stop() 在部分 ROM 上抛异常会中断本函数，
        // isRunning 保持 true、界面显示"守护中"，但检测循环已死——
        // 主页表现为"巡检心跳停在 xx 秒前"。
        settings.serviceRunning = false
        isRunning = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "守护已停止")
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            // 使用时长闸门的检查频率必须比 AI 检测密得多，
            // 否则用户超限后还能继续用好几分钟才被拦下
            var msUntilNextDetection = currentDetectionDelayMs()

            while (isActive) {
                // 整个循环体加保护：任何环节抛异常（部分 ROM 的 queryUsageStats、
                // startActivity 等）都会终止协程 → 检测永久停止 → "开启守护后
                // 卡住、不弹日志"。异常记录后继续循环。
                try {
                    delay(USAGE_TICK_MS)
                    if (!isActive) break

                    // 亮屏边沿：刚点亮屏幕立刻补一次检测，
                    // 不让"息屏期间攒下的违规"等到下一个周期才被发现
                    val interactive = isScreenInteractive()
                    if (interactive && !lastScreenInteractive) {
                        Log.d(TAG, "屏幕点亮，立即执行一次检测")
                        msUntilNextDetection = 0L
                    }
                    lastScreenInteractive = interactive

                    // 锁机兜底：锁机状态激活但锁机页不在前台（被最近任务/滑动销毁、
                    // 进程被杀后服务重建）时，自动重新拉起锁机页
                    enforceLockReassert()

                    // 每个 tick 都累计使用时长并检查是否触发闸门
                    when (val verdict = usageTick()) {
                        is UsageTracker.Verdict.ShouldHardBlock -> {
                            handleHardBlock(verdict)
                            // 已封锁，本轮不必再花 token 做 AI 检测
                            msUntilNextDetection = currentDetectionDelayMs()
                            continue
                        }
                        is UsageTracker.Verdict.ShouldDetect -> {
                            // 越过触发阈值，立刻转入检测模式而不等原定周期
                            if (!usageTriggeredDetection) {
                                usageTriggeredDetection = true
                                Log.d(
                                    TAG,
                                    "${verdict.packageName} 使用 ${verdict.usedMinutes} 分钟" +
                                        "（阈值 ${verdict.triggerMinutes}），开始 AI 检测"
                                )
                                msUntilNextDetection = 0L
                            }
                        }
                        UsageTracker.Verdict.Idle -> usageTriggeredDetection = false
                    }

                    // 心跳：证明循环还活着（排查"守护开着但不检测"用）
                    lastTickAt = System.currentTimeMillis()

                    // 采集通道可能随时变化（无障碍被开启/被系统回收），同步给 UI 与通知
                    updateCaptureChannel()

                    msUntilNextDetection -= USAGE_TICK_MS
                    if (msUntilNextDetection <= 0) {
                        performDetection(isManualTest = false)
                        lastDetectionAt = System.currentTimeMillis()
                        msUntilNextDetection = currentDetectionDelayMs()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 真正的协程取消（服务停止）→ 正常退出循环
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "巡检循环异常（已恢复，继续检测）：${e.message}")
                }
            }
        }
    }

    /**
     * 锁机兜底：锁机状态激活、锁机页不在前台且无答题页时，
     * 重新拉起锁机页。防止用户通过最近任务/滑动销毁锁机页绕过锁机。
     */
    private fun enforceLockReassert() {
        try {
            if (!lockState.isLocked || !lockState.shouldBlockNow) return
            // 悬浮窗已是主体：只要它还挂着就算受控，无需再拉 Activity
            if (com.focusguard.app.enforce.LockOverlayManager.isShowing) return
            // 答题页启动中或已创建时均让位；只认 foreground 会在
            // startActivity→onResume 的窗口里把锁机页顶回，造成答题自动退出。
            if (com.focusguard.app.enforce.UnlockChallengeActivity.active) return
            if (com.focusguard.app.enforce.LockScreenActivity.friendUnlockActive) return
            // 锁机页在前台也算受控
            if (com.focusguard.app.enforce.LockScreenActivity.instance != null) return

            Log.d(TAG, "锁机状态激活但无任何防线在前台，自动重新拉起")
            com.focusguard.app.enforce.LockScreenActivity.show(this)
        } catch (e: Exception) {
            Log.w(TAG, "锁机兜底拉起失败：${e.message}")
        }
    }

    /** 当前应等待的检测间隔，自适应开启时由调度器决定。 */
    private fun currentDetectionDelayMs(): Long {
        // 每次都读取最新设置的间隔——用户改完设置立即生效，
        // 不需要重启守护（此前用服务启动时的旧值，改设置后仍按旧间隔检测，
        // 表现为"设了 1 分钟却 3-4 分钟才检测一次"）
        val currentInterval = settings.intervalMinutes.coerceAtLeast(1)

        // ── 智能模式（默认）：秒级动态间隔 ──────────────
        val smart = smartScheduler
        if (settings.smartScheduleEnabled && smart != null) {
            smart.updateBase(currentInterval)
            val screenOn = isScreenInteractive()
            val fg = try {
                com.focusguard.app.detection.AppClassifier
                    .classifyForegroundApp(this, categoryStore)?.packageName
            } catch (e: Exception) {
                null
            }
            // 让调度器学习该应用的停留时长
            smart.onForegroundApp(fg)
            var seconds = smart.nextDelaySeconds(
                screenOn = screenOn,
                foregroundPackage = fg,
                lastWasError = lastDetectionWasError
            )
            // 息屏但媒体仍在播放（挂着视频/直播/短视频）时不能按省电上限睡过去，
            // 否则锁屏挂着视频就能一直躲过检测
            val mediaPlayingWithScreenOff =
                !screenOn && MediaPlaybackDetector.isPlaying(this)
            if (mediaPlayingWithScreenOff) {
                val awakeInterval = (currentInterval * 60L).coerceAtLeast(30L)
                if (seconds > awakeInterval) seconds = awakeInterval
            }
            // 暴露给 UI 展示
            smartIntervalSeconds = seconds
            smartRiskPercent = (smart.currentRisk() * 100).toInt()
            smartReason = if (mediaPlayingWithScreenOff) {
                "息屏但仍有媒体在播放，保持常规检测频率（${smart.lastReason}）"
            } else {
                smart.lastReason
            }
            return seconds * 1000L
        }

        // ── 传统自适应（倍率）──────────────────────────
        val sched = scheduler
        if (settings.adaptiveIntervalEnabled && sched != null) {
            // 用当前倍率 × 最新基准间隔（倍率上限已温和化为 2x）
            val mult = sched.currentMultiplier().toDouble()
            val delayMs = (currentInterval * 60_000L * mult).toLong()
            return delayMs.coerceAtLeast(currentInterval * 30_000L)
        }
        return currentInterval * 60_000L
    }

    /** 屏幕是否亮着（PowerManager 不可用时按"亮着"处理，宁可多检测不漏判）。 */
    private fun isScreenInteractive(): Boolean = try {
        getSystemService(PowerManager::class.java)?.isInteractive ?: true
    } catch (e: Exception) {
        true
    }

    /**
     * 采样一次前台应用并累计使用时长。
     *
     * 排除自身与封锁界面：否则用户被封锁后停留在封锁页，
     * 封锁页自己的驻留时间会被算进被封应用的时长。
     */
    private fun usageTick(): UsageTracker.Verdict {
        val foreground = AppClassifier.classifyForegroundApp(this, categoryStore)
        val pkg = foreground?.packageName
        val countable = pkg?.takeUnless { it == packageName }
        return usageTracker.tick(countable)
    }

    /** 触发应用硬封锁：拉起全屏封锁页，并记入日志。 */
    private fun handleHardBlock(verdict: UsageTracker.Verdict.ShouldHardBlock) {
        val label = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(verdict.packageName, 0)
            ).toString()
        }.getOrDefault(verdict.packageName)

        Log.d(
            TAG,
            "$label 已用 ${verdict.usedMinutes} 分钟，超过上限 ${verdict.limitMinutes} 分钟，执行封锁"
        )

        AppBlockActivity.show(
            context = this,
            packageName = verdict.packageName,
            appLabel = label,
            usedMinutes = verdict.usedMinutes,
            limitMinutes = verdict.limitMinutes
        )

        logStore.addLog(
            DetectionLog(
                classification = "ENTERTAINMENT",
                confidence = 1f,
                reason = "$label 使用时长 ${verdict.usedMinutes} 分钟已达上限 ${verdict.limitMinutes} 分钟",
                action = "APP_BLOCK",
                source = DetectionSource.APP_CATEGORY.name,
                appLabel = label
            )
        )
    }

    private suspend fun performDetection(isManualTest: Boolean) {
        // 流水线按需创建：即使没有屏幕共享也能工作（无画面走事件层判定）
        val activePipeline = ensurePipeline()

        // 锁机期间无需继续检测，省电也省 token
        if (!isManualTest && lockState.isLocked) {
            Log.d(TAG, "锁机中，跳过本轮检测")
            return
        }

        val outcome = try {
            activePipeline.detect()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程真正被取消（服务停止/被杀）→ 静默向上传播，不记录假错误。
            // 注意：判断要反过来——此前写成 isActive 时 rethrow，导致
            // 真正的取消被当成"检测异常：StandaloneCoroutine was cancelled"
            // 记入日志（误导排查）。
            if (!kotlin.coroutines.coroutineContext.isActive) throw e
            // 协程仍活跃的"假取消"（个别库抛的 TimeoutCancellationException）
            // 按普通异常记录，避免整个检测循环被误杀
            Log.e(TAG, "检测流程异常（假取消）", e)
            DetectionOutcome(
                classification = "NEUTRAL",
                confidence = 0f,
                reason = "检测异常：${e.message}",
                source = DetectionSource.ERROR
            )
        } catch (e: Exception) {
            Log.e(TAG, "检测流程异常", e)
            DetectionOutcome(
                classification = "NEUTRAL",
                confidence = 0f,
                reason = "检测异常：${e.message}",
                source = DetectionSource.ERROR
            )
        }

        lastOutcome = outcome
        Log.d(TAG, "检测结果 ${outcome.classification} ${outcome.confidence} 来源=${outcome.source}")

        val isViolation = outcome.classification == "ENTERTAINMENT" &&
            outcome.confidence >= settings.confidenceThreshold

        var action = "NONE"
        if (isViolation) {
            consecutiveViolations++
            action = if (consecutiveViolations >= settings.consecutiveViolations.coerceAtLeast(1)) {
                consecutiveViolations = 0
                if (isManualTest) {
                    // 手动测试只报告结果，不真的锁机
                    "WARN"
                } else {
                    enforceWithAlert(outcome)
                }
            } else {
                // 未达连续次数：只弹提醒，不锁机（提前给用户信号）
                if (settings.aiAlertEnabled) {
                    AlertNotifier.alertEntertainment(
                        context = this,
                        title = "⚠️ 检测到娱乐行为",
                        message = outcome.reason,
                        countdownSeconds = 0
                    )
                }
                // 智能调度：每次提醒后间隔折半，尽快确认用户是否收手
                smartScheduler?.onWarned()
                "WARN"
            }
        } else if (outcome.source != DetectionSource.ERROR) {
            consecutiveViolations = 0
            // 已回到学习/中性状态：撤掉之前的提醒横幅
            AlertNotifier.cancelAlert(this)
            smartScheduler?.onCalm()
        }

        logStore.addLog(
            DetectionLog(
                classification = outcome.classification,
                confidence = outcome.confidence,
                reason = outcome.reason,
                action = action,
                source = outcome.source.name,
                appLabel = outcome.appLabel
            )
        )

        // 调度反馈：学习→逐步放宽间隔，娱乐→立刻加密
        try {
            if (outcome.source == DetectionSource.PRIVACY_SKIP) {
                // 隐私跳过不含任何行为信息：不参与风险计算，
                // 否则"打开银行 app"会被当成中性 0.35 混入 EWMA，无谓推高风险
                lastDetectionWasError = false
            } else {
                scheduler?.onResult(outcome.classification)
                // 智能调度：风险 EWMA 更新 + 错误退避标记
                lastDetectionWasError = outcome.source == DetectionSource.ERROR
                smartScheduler?.onDetectionResult(
                    outcome.classification,
                    isError = lastDetectionWasError
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "调度反馈失败：${e.message}")
        }

        updateNotification(outcome)
    }

    /**
     * 达到执法条件时的处理：先弹横幅提醒 + 宽限期，再真正锁机。
     *
     * 宽限期内**只要离开娱乐现场就算免锁**：退回桌面、切到学习/社交/系统界面、
     * 甚至直接息屏，都算「已退出」。由 [gracePeriodJob] 复检前台应用来判定。
     * 关闭"提醒后锁机"或宽限秒数为 0 时立即锁机（旧行为）。
     */
    private fun enforceWithAlert(outcome: DetectionOutcome): String {
        val delaySeconds = if (settings.aiAlertEnabled) settings.aiAlertDelaySeconds else 0

        if (delaySeconds <= 0) {
            AlertNotifier.alertEntertainment(
                context = this,
                title = "🔒 已锁机",
                message = outcome.reason
            )
            return doEnforce(outcome)
        }

        // 弹提醒 + 倒计时说明
        AlertNotifier.alertEntertainment(
            context = this,
            title = "⚠️ 检测到娱乐行为",
            message = outcome.reason,
            countdownSeconds = delaySeconds
        )

        // 宽限期结束后复检：仍在娱乐 → 锁机；已退出 → 免锁
        //
        // 已经在宽限期内就不再重复计时：否则每检测到一次娱乐就把倒计时重置一次，
        // 用户一直赖着也能永远躲过锁机。
        if (gracePeriodJob?.isActive == true) {
            Log.d(TAG, "宽限期进行中，不重复计时")
            return "WARN"
        }

        gracePeriodJob = serviceScope.launch {
            try {
                delay(delaySeconds * 1000L)

                // 「退出」的判定必须宽。旧实现写的是：
                //     fg == null || classifyByAppInfo(fg) != "STUDY_WORK"
                // 含义变成"只有切到学习类 App 才算退出"——
                // 于是按 Home 退回桌面（SYSTEM/NEUTRAL）反而被判成"仍在娱乐"，
                // 用户明明退出了还是被锁机。下面按"是否还在娱乐现场"重新判定。
                val fg = AppClassifier.classifyForegroundApp(this@MonitorService, categoryStore)
                val stillEntertainment = when {
                    // 已经息屏：用户不看了，别把他叫醒再锁机
                    !isScreenInteractive() -> false
                    // 读不到前台：宁可漏锁，也不能凭"读不到"就定罪
                    fg == null -> false
                    // 还赖在触发提醒的那个 App 里
                    fg.packageName == outcome.packageName -> true
                    // 换个游戏继续
                    fg.category == AppCategory.GAME -> true
                    // 换个视频 App 继续（B 站 / 抖音 / 快手 ……）
                    fg.category == AppCategory.VIDEO -> true
                    fg.category == AppCategory.SHORT_VIDEO -> true
                    // 桌面、系统界面、学习类、社交、未知 → 都算已经退出
                    else -> false
                }

                if (!stillEntertainment) {
                    val fgPkg = fg?.packageName.orEmpty()
                    val fgLabel = fg?.label.orEmpty()
                    Log.d(
                        TAG,
                        "宽限期内已退出娱乐应用（前台=${fgPkg.ifBlank { "读不到" }}），免除锁机"
                    )
                    AlertNotifier.cancelAlert(this@MonitorService)
                    logStore.addLog(
                        DetectionLog(
                            classification = "NEUTRAL",
                            confidence = 1f,
                            reason = "宽限期内已退出娱乐应用（前台：" +
                                fgLabel.ifBlank { fgPkg.ifBlank { "未知" } } +
                                "），免除锁机",
                            action = "NONE",
                            source = DetectionSource.APP_CATEGORY.name,
                            appLabel = fgLabel
                        )
                    )
                    return@launch
                }

                Log.d(TAG, "宽限期结束仍在娱乐，执行锁机")
                AlertNotifier.alertEntertainment(
                    context = this@MonitorService,
                    title = "🔒 已锁机",
                    message = outcome.reason
                )
                val enforceAction = doEnforce(outcome)
                // 补记执法日志：宽限期路径在 performDetection 里记录的是"警告"，
                // 真正锁机发生在这里——不补记的话用户会看到"明明锁机了日志却显示警告"
                logStore.addLog(
                    DetectionLog(
                        classification = outcome.classification,
                        confidence = outcome.confidence,
                        reason = "宽限期结束仍在娱乐，已执行锁机",
                        action = enforceAction, // LOCK / APP_BLOCK
                        source = outcome.source.name,
                        appLabel = outcome.appLabel
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 服务停止，正常退出
            } catch (e: Exception) {
                Log.w(TAG, "宽限期处理异常：${e.message}")
            }
        }
        return "WARN"
    }

    /** 真正执行执法动作（锁机 / 仅锁该软件 / 警告）。 */
    private fun doEnforce(outcome: DetectionOutcome): String {
        // 智能调度：执法后提醒计数归零、风险回落
        smartScheduler?.onEnforced()
        val performed = enforcer.enforce(
            settings.enforcementMode,
            outcome.reason,
            outcome.packageName,
            outcome.appLabel
        )
        if (settings.enforcementMode != Settings.EnforcementMode.WARN) {
            // 「仅锁该软件」模式：不触发全局锁机，只封锁该应用
            if (settings.enforcementMode == Settings.EnforcementMode.APP_BLOCK) {
                return performed
            }
            // 应用设置里配置的 AI 执法解锁强度；已在锁机时只加码不覆盖
            // （强度 3 的朋友密文由 startLock 内部生成）
            lockState.startLock(settings.lockMinutesOnViolation, "AI", settings.aiLockStrength)
        }
        return performed
    }

    private fun updateNotification(outcome: DetectionOutcome) {
        // 所有检测结果都更新常驻通知（显示在通知栏）。
        // 该渠道为 IMPORTANCE_LOW（静默）：学习/工作时只显示、不弹出横幅、
        // 不响铃、不打扰；娱乐时的"弹出式提醒"由 AlertNotifier 独立负责。
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.notify(FocusGuardApp.NOTIFICATION_ID, buildNotification(outcome))
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败: ${e.message}")
        }
    }

    private fun buildNotification(outcome: DetectionOutcome?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelSuffix = "\n画面通道：" + captureChannelText()
        val text = when {
            outcome == null -> getString(R.string.notification_text) + channelSuffix
            else -> buildString {
                append("判断：")
                append(
                    when (outcome.classification) {
                        "ENTERTAINMENT" -> "娱乐"
                        "STUDY_WORK" -> "学习/工作"
                        else -> "中性"
                    }
                )
                append("（${(outcome.confidence * 100).toInt()}%）")
                if (outcome.appLabel.isNotBlank()) {
                    append(" · ")
                    append(outcome.appLabel)
                }
                append("\n来源：")
                append(outcome.source.name)
                if (outcome.reason.isNotBlank()) {
                    append(" · ")
                    append(outcome.reason.replace('\n', ' ').take(80))
                }
                append(channelSuffix)
            }
        }

        return Notification.Builder(this, FocusGuardApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(
                android.app.Notification.BigTextStyle()
                    .bigText(text)
            )
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        // 刻意**不**在这里清掉 settings.serviceRunning：
        // 这个标记表示"用户开着守护"，只有用户在界面上停止守护时才该被清掉
        // （见 stopMonitoring）。系统因内存回收等原因销毁本服务时也会走 onDestroy，
        // 若在这里清标记，START_STICKY 重建后就再也接不上守护了。
        isRunning = false
    }
}
