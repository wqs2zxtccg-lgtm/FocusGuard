package com.focusguard.app.detection

import android.content.Context
import android.util.Log
import com.focusguard.app.ai.AiClient
import com.focusguard.app.capture.FrameSource
import com.focusguard.app.data.Settings
import com.focusguard.app.token.DecisionCache
import com.focusguard.app.token.ImageHasher
import com.focusguard.app.token.TokenBudget

/**
 * 检测来源。用于日志展示、统计节约效果，也方便排查误判。
 */
enum class DetectionSource {
    WHITELIST,      // 用户白名单，零 token
    APP_CATEGORY,   // 应用分类直接判定，零 token
    SCREEN_TEXT,    // 屏幕文字规则判定，零 token
    SCREEN_UNCHANGED, // 画面与上次几乎相同，复用上次结论，零 token
    CACHE_HIT,      // 感知哈希命中历史判定，零 token
    BLANK_SCREEN,   // 息屏/纯色屏，无识别价值，零 token
    NO_FRAME,       // 无可用画面（息屏/屏幕共享结束/无障碍截图不可用）→ 事件层判定，零 token
    MEDIA_PLAYBACK, // 无画面，但"媒体正在播放 + 前台是娱乐类应用"，零 token
    BUDGET_EXCEEDED, // 今日配额用尽，退化为本地判定
    AI_VISION,      // 视觉大模型判定，消耗 token
    PRIVACY_SKIP,   // 敏感应用隐私保护：不截屏、不读取、不上传
    ERROR
}

data class DetectionOutcome(
    val classification: String,   // STUDY_WORK / ENTERTAINMENT / NEUTRAL
    val confidence: Float,
    val reason: String,
    val source: DetectionSource,
    val packageName: String = "",
    val appLabel: String = ""
) {
    /** 本次判定是否真的花了 token。 */
    val consumedToken: Boolean get() = source == DetectionSource.AI_VISION
}

/**
 * 多级检测流水线，目标是让视觉大模型的调用次数降到最低。
 *
 * 判定顺序（越靠前越省钱，命中即返回）：
 *
 *  L0 隐私保护      前台是敏感应用（银行/支付/密码管理等）→
 *                   不截屏、不读文字、不上传，只记一条隐私日志
 *  L1 白名单        用户显式声明的应用 → 直接放行
 *  L2 应用分类      纯游戏 / 纯学习 / 系统应用 → 直接定论
 *  L3 屏幕文字      无障碍读文字，关键词打分 → 有明显倾向就定论
 *  L4 画面去重      与上一帧感知哈希几乎相同 → 复用上次结论
 *  L5 空白画面      息屏、纯色屏 → 判为中性
 *  L6 判定缓存      历史上见过高度相似的画面 → 复用历史结论
 *  L7 预算闸门      今日 token 配额耗尽 → 退化为本地保守判定
 *  L8 视觉大模型    以上都无法定论时才真正调用
 *
 *  无画面分支：L4~L8 全部依赖画面。拿不到画面时（息屏、屏幕共享被
 *  系统结束、无障碍截图不可用）不再当作错误，而是走事件层降级判定：
 *  「媒体正在播放 + 前台是娱乐类应用」→ 判娱乐，否则中性。
 *
 * 关键设计取舍：视频平台不因"是视频应用"就判娱乐。
 * B 站上的编程教程必须能被识别为学习，宁可多花一次 token，
 * 也不能误杀正常的学习行为。
 */
class DetectionPipeline(
    private val context: Context,
    private val settings: Settings,
    private val aiClient: AiClient,
    /**
     * 当前可用的画面通道。返回 null 表示此刻一条通道都没有
     * （屏幕共享已结束、无障碍截图也不可用）——交给 [detectWithoutFrame]。
     */
    private val frameProvider: () -> FrameSource?,
    private val tokenBudget: TokenBudget,
    private val decisionCache: DecisionCache,
    private val categoryStore: AppCategoryStore
) {

    companion object {
        private const val TAG = "DetectionPipeline"
        /** 相邻两帧判定为"未变化"的汉明距离阈值。 */
        private const val UNCHANGED_THRESHOLD = 4
    }

    private var lastHash: Long? = null
    private var lastPackage: String? = null
    private var lastClassification: String? = null
    private var lastConfidence: Float = 0f
    private var lastReason: String = ""

    /**
     * 各级开关。用户在设置里关掉 Token 节约系统后，
     * L3 文字预过滤、L4 画面去重、L6 判定缓存全部跳过，
     * 每轮检测都直接调用视觉大模型。
     *
     * L0 隐私保护、L1 白名单、L2 应用分类、L5 空白画面保持始终生效：
     * 这些是零成本的常识判断（隐私边界、用户显式声明、纯游戏应用、息屏），
     * 没有任何理由花钱去问模型"现在是不是息屏"。
     */
    private val dedupOn: Boolean get() = settings.screenHashDedupEnabled
    private val textPrefilterOn: Boolean get() = settings.screenTextPrefilterEnabled
    private val cacheOn: Boolean get() = settings.decisionCacheEnabled

    suspend fun detect(): DetectionOutcome {
        val appInfo = AppClassifier.classifyForegroundApp(context, categoryStore)
        val pkg = appInfo?.packageName.orEmpty()
        val label = appInfo?.label.ifNullOrBlank { pkg }

        // ── L0 隐私保护（优先于一切判定，含白名单）─────────────────
        // 银行/支付/密码管理等敏感应用的内容绝不能离开设备。即使该应用
        // 被加进白名单，也只是"不判违规"，用户未必愿意把截图发出去——
        // 隐私优先级高于检测功能：本轮不截屏、不读屏幕文字、不上传。
        if (settings.privacyProtectEnabled && pkg.isNotBlank()) {
            val sensitive = com.focusguard.app.privacy.PrivacyGuard.isSensitive(
                pkg, label, settings.sensitiveApps
            )
            if (sensitive) {
                return DetectionOutcome(
                    "NEUTRAL", 1f,
                    "隐私保护：$label 属于敏感应用，本轮已跳过截屏与内容读取",
                    DetectionSource.PRIVACY_SKIP, pkg, label
                )
            }
        }

        // ── L1 白名单 ─────────────────────────────────
        if (isWhitelisted(label, pkg)) {
            return saved(
                DetectionOutcome(
                    "STUDY_WORK", 1f, "白名单放行：$label",
                    DetectionSource.WHITELIST, pkg, label
                )
            )
        }

        // ── L2 应用分类 ───────────────────────────────
        if (appInfo != null) {
            val verdict = AppClassifier.classifyByAppInfo(appInfo)
            if (verdict != "NEEDS_AI") {
                val reason = when (verdict) {
                    "ENTERTAINMENT" -> "$label 属于游戏/娱乐类应用"
                    "STUDY_WORK" -> "$label 属于学习/办公类应用"
                    else -> "$label 属于系统或中性界面"
                }
                return saved(
                    DetectionOutcome(verdict, 0.95f, reason, DetectionSource.APP_CATEGORY, pkg, label)
                )
            }
        }

        // ── L3 屏幕文字（可关闭）──────────────────────
        if (textPrefilterOn) {
            ScreenTextReader.readCurrentScreenText()?.let { text ->
                // 完整词表（内置默认 + 用户编辑，设置页可查看/修改）
                val settings = com.focusguard.app.data.Settings(context)
                val textVerdict = AppClassifier.classifyByScreenText(
                    text,
                    settings.studyKeywordList(),
                    settings.entertainmentKeywordList()
                )
                if (textVerdict == "STUDY_WORK" || textVerdict == "ENTERTAINMENT") {
                    val reason = if (textVerdict == "STUDY_WORK") {
                        "屏幕文字含学习/工作特征（$label）"
                    } else {
                        "屏幕文字含娱乐特征（$label）"
                    }
                    return saved(
                        DetectionOutcome(textVerdict, 0.8f, reason, DetectionSource.SCREEN_TEXT, pkg, label)
                    )
                }
            }
        }

        // ── 以下步骤需要画面 ──────────────────────────
        // 关键改动：取不到画面不再等于"检测失败"。屏幕共享在息屏/锁屏后
        // 必然失效，若把它当成错误（旧实现还会顺手停掉整个守护服务），
        // 用户一息屏这套软件就完全失效了。
        val source = frameProvider()
        val capture = source?.takeIf { it.available }?.grab()
        if (capture == null) {
            return detectWithoutFrame(pkg, label, source)
        }

        try {
            val hash = ImageHasher.dHash(capture.bitmap)

            // ── L4 画面去重（可关闭）─────────────────
            val prevHash = lastHash
            if (dedupOn &&
                prevHash != null &&
                lastPackage == pkg &&
                lastClassification != null &&
                ImageHasher.isSimilar(prevHash, hash, UNCHANGED_THRESHOLD)
            ) {
                Log.d(TAG, "画面未变化，复用上次结论")
                return saved(
                    DetectionOutcome(
                        lastClassification!!, lastConfidence,
                        "画面未变化，沿用上次判定（$lastReason）",
                        DetectionSource.SCREEN_UNCHANGED, pkg, label
                    )
                )
            }

            // ── L5 空白画面 ───────────────────────────
            if (ImageHasher.isNearlyBlank(capture.bitmap)) {
                remember(hash, pkg, "NEUTRAL", 0.9f, "息屏或纯色画面")
                return saved(
                    DetectionOutcome(
                        "NEUTRAL", 0.9f, "息屏或纯色画面，无需识别",
                        DetectionSource.BLANK_SCREEN, pkg, label
                    )
                )
            }

            // ── L6 判定缓存（可关闭）─────────────────
            if (cacheOn) {
                decisionCache.lookup(pkg, hash)?.let { cached ->
                    remember(hash, pkg, cached.classification, cached.confidence, cached.reason)
                    return saved(
                        DetectionOutcome(
                            cached.classification, cached.confidence,
                            "命中历史判定：${cached.reason}",
                            DetectionSource.CACHE_HIT, pkg, label
                        )
                    )
                }
            }

            // ── L7 预算闸门 ───────────────────────────
            // 每次都从 SharedPreferences 重新读取，而不是构造时缓存，
            // 否则用户在设置里改完密钥后仍会看到"未配置"
            val currentApiKey = settings.apiKey
            if (currentApiKey.isBlank()) {
                return DetectionOutcome(
                    "NEUTRAL", 0f, "未配置 API 密钥，请在设置中填写并保存",
                    DetectionSource.ERROR, pkg, label
                )
            }
            if (!tokenBudget.canCallAi()) {
                Log.w(TAG, "今日 AI 配额已用尽，退化为本地判定")
                return DetectionOutcome(
                    "NEUTRAL", 0.3f,
                    "今日 AI 配额已用尽（${tokenBudget.dailyCallLimit} 次），仅做本地判定",
                    DetectionSource.BUDGET_EXCEEDED, pkg, label
                )
            }

            // ── L8 视觉大模型 ─────────────────────────
            // 把备忘录未完成事项注入提示词：检测到娱乐时，
            // 让模型在提醒语里引用待办（如"你还有 xxx 没做呢"）
            val memoText = runCatching {
                com.focusguard.app.data.MemoStore(context).promptSummary()
            }.getOrDefault("")
            val effectivePrompt = buildString {
                append(settings.aiCustomPrompt)
                if (memoText.isNotBlank()) {
                    append("\n\n用户的待办事项（检测到娱乐时，在提醒语里引用还没做完的事，鼓励用户去做）：\n")
                    append(memoText)
                }
            }

            val aiResult = aiClient.analyzeScreen(
                imageBytes = capture.jpegBytes,
                baseUrl = settings.apiBaseUrl,
                apiKey = currentApiKey,
                modelName = settings.modelName,
                whitelist = settings.whitelist,
                customPrompt = effectivePrompt,
                apiFormat = settings.apiFormat
            )
            tokenBudget.recordCall()

            val reason = aiResult.reason.ifBlank { "AI 视觉识别" }
            // 只有缓存开启时才写入缓存，关闭时用户期望每次都新鲜判定
            if (cacheOn) {
                decisionCache.put(pkg, hash, aiResult.classification, aiResult.confidence, reason)
            }
            // AI 判定稳定时回写到应用级学习缓存（GAME/STUDY 才做，避免内容随时变的应用误学）
            AppClassifier.learnFromAiResult(pkg, aiResult.classification, aiResult.confidence, categoryStore)
            remember(hash, pkg, aiResult.classification, aiResult.confidence, reason)

            Log.d(TAG, "AI 判定 ${aiResult.classification} ${aiResult.confidence}，今日已用 ${tokenBudget.callsToday} 次")

            return DetectionOutcome(
                aiResult.classification, aiResult.confidence, reason,
                DetectionSource.AI_VISION, pkg, label
            )
        } finally {
            capture.recycle()
        }
    }

    /**
     * 无画面时的降级判定（事件层）。
     *
     * 用的全是零成本信号：前台应用分类 + 媒体播放状态，不花一分钱 token。
     * 判定刻意保守——**两个条件必须同时成立**才判娱乐：
     *   1. 确实有媒体在播放（息屏挂着视频/直播/短视频的典型特征，通话/提示音不算）
     *   2. 前台（或最近前台）应用属于**高可信度**的游戏 / 长视频 / 短视频类
     *
     * 两个刻意的保守取舍：
     *  · 分类只认内置静态表与用户手动指定，**不采用元数据推断**——
     *    推断靠包名/名称关键字（例如 "tv"），没有画面佐证时误判代价太大。
     *  · **社交类不参与**（微信、QQ、小红书里的声音多半是语音消息或视频通话）。
     *
     * 一句话：宁可漏判，也不能把边听歌、听语音的用户锁在外面。
     */
    private fun detectWithoutFrame(
        pkg: String,
        label: String,
        source: FrameSource?
    ): DetectionOutcome {
        // 息屏后 queryUsageStats 常常读不到事件，退回"最近前台应用"缓存
        val effectivePackage = if (pkg.isNotBlank()) pkg else recentForegroundPackage().orEmpty()
        val effectiveLabel = label.ifBlank { appLabelOf(effectivePackage) }

        // 高可信度分类：内置静态表 + 用户手动覆盖（取舍见方法注释）
        val category = AppClassifier.builtinCategory(effectivePackage)
            ?: categoryStore.getUserOverride(effectivePackage)

        val media = try {
            MediaPlaybackDetector.snapshot(context)
        } catch (e: Exception) {
            Log.w(TAG, "读取媒体播放状态失败：${e.message}")
            null
        }

        // 只认明确的娱乐类应用；社交类不参与无画面判定（见上方说明）
        val entertainmentCategory = category == AppCategory.GAME ||
            category == AppCategory.VIDEO ||
            category == AppCategory.SHORT_VIDEO

        if (media != null && media.playing && entertainmentCategory) {
            val name = effectiveLabel.ifBlank { effectivePackage }
            val kind = if (media.movieLike) "视频/媒体" else "音频"
            val reason = "无画面但检测到${kind}正在播放（${media.detail}），" +
                "且前台是娱乐类应用：$name"
            return saved(
                DetectionOutcome(
                    "ENTERTAINMENT", 0.75f, reason,
                    DetectionSource.MEDIA_PLAYBACK, effectivePackage, effectiveLabel
                )
            )
        }

        val reason = when {
            source == null ->
                "无可用画面通道（屏幕共享已结束，无障碍截图也不可用），本轮仅做事件层判定"
            !source.available ->
                "${source.name} 通道当前不可用，本轮仅做事件层判定"
            else ->
                "${source.name} 通道取不到画面（可能已息屏/锁屏），本轮仅做事件层判定"
        }
        return DetectionOutcome(
            "NEUTRAL", 0.2f, reason,
            DetectionSource.NO_FRAME, effectivePackage, effectiveLabel
        )
    }

    /** 最近一次前台应用包名（使用统计事件缓存，息屏后仍然可读）。 */
    private fun recentForegroundPackage(): String? = try {
        com.focusguard.app.service.ForegroundAppDetector.current(context)
            ?.takeIf { it != context.packageName }
    } catch (e: Exception) {
        null
    }

    /** 包名 → 应用名，读不到就退回包名。 */
    private fun appLabelOf(packageName: String): String {
        if (packageName.isBlank()) return ""
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /** 记录一次成功避免的调用，并返回原结果，方便链式书写。 */
    private fun saved(outcome: DetectionOutcome): DetectionOutcome {
        tokenBudget.recordSavedCall()
        return outcome
    }

    private fun remember(
        hash: Long,
        pkg: String,
        classification: String,
        confidence: Float,
        reason: String
    ) {
        lastHash = hash
        lastPackage = pkg
        lastClassification = classification
        lastConfidence = confidence
        lastReason = reason
    }

    /** 用户手动停止或屏幕熄灭后清掉上一帧状态，避免跨会话误复用。 */
    fun resetFrameState() {
        lastHash = null
        lastPackage = null
        lastClassification = null
        lastConfidence = 0f
        lastReason = ""
    }

    private fun isWhitelisted(label: String, packageName: String): Boolean {
        val raw = settings.whitelist
        if (raw.isBlank()) return false
        return raw.split(',', '，', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { entry ->
                label.contains(entry, ignoreCase = true) ||
                    packageName.contains(entry, ignoreCase = true)
            }
    }
}

private inline fun String?.ifNullOrBlank(fallback: () -> String): String =
    if (this.isNullOrBlank()) fallback() else this
