package com.focusguard.app.detection

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.util.Log

/**
 * 媒体播放状态。
 *
 * @param playing   当前是否有音频/视频在播放
 * @param movieLike 播放内容偏向"视频/影视"（而不是纯音乐）
 * @param detail    供日志展示的判定依据
 */
data class MediaPlaybackState(
    val playing: Boolean,
    val movieLike: Boolean,
    val detail: String
)

/**
 * 媒体播放探测器——"事件层判定"的核心信号之一。
 *
 * 存在的意义：息屏之后拿不到画面，但"有没有东西在响"是拿得到的。
 * 用户在息屏状态下听视频/挂着直播，这个信号依然有效，
 * 而且是零权限、零成本、零 token 的（[AudioManager.isMusicActive] 不需要任何权限）。
 *
 * 保守原则：这里只提供证据，不单独定罪。
 * 单纯"在放音乐"绝不能判定为娱乐——不然边听歌边学习会被误锁。
 * 真正的判定放在 [DetectionPipeline] 里，必须叠加"前台应用属于娱乐类"才成立。
 */
object MediaPlaybackDetector {

    private const val TAG = "MediaPlaybackDetector"

    private val empty = MediaPlaybackState(false, false, "无活跃播放")

    fun snapshot(context: Context): MediaPlaybackState {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return MediaPlaybackState(false, false, "无法访问音频服务")

        var playing = false
        var movieLike = false
        val parts = mutableListOf<String>()

        // ① 最可靠、且明确不需要权限的信号
        try {
            if (am.isMusicActive) {
                playing = true
                parts += "音频活跃"
            }
        } catch (e: Exception) {
            Log.d(TAG, "读取 isMusicActive 失败：${e.message}")
        }

        // ② 更细粒度的播放配置（API 26+）。
        // 部分 ROM 会对非系统应用隐藏其他应用的播放配置，
        // 因此这一段只做"尽力而为"的增强，失败不影响 ① 的结论。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                for (config in am.activePlaybackConfigurations) {
                    if (config.playerState != AudioPlaybackConfiguration.PLAYER_STATE_STARTED) continue
                    val attributes = config.audioAttributes
                    val usage = attributes?.usage ?: AudioAttributes.USAGE_UNKNOWN
                    val contentType = attributes?.contentType ?: AudioAttributes.CONTENT_TYPE_UNKNOWN

                    // 只认"媒体类"播放：通话、通知、闹钟、导航播报一律不算。
                    // 否则息屏时接个微信语音、或者导航播报，都会被当成在娱乐。
                    val mediaUsage = usage == AudioAttributes.USAGE_MEDIA ||
                        usage == AudioAttributes.USAGE_GAME ||
                        usage == AudioAttributes.USAGE_UNKNOWN
                    if (!mediaUsage) continue

                    playing = true
                    parts += "usage=$usage/content=$contentType"
                    if (usage == AudioAttributes.USAGE_MEDIA ||
                        contentType == AudioAttributes.CONTENT_TYPE_MOVIE
                    ) {
                        movieLike = true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "读取播放配置失败：${e.message}")
            }
        }

        if (!playing) return empty
        return MediaPlaybackState(
            playing = true,
            movieLike = movieLike,
            detail = parts.joinToString("、").ifBlank { "有播放但无法识别类型" }
        )
    }

    /** 便捷判断：现在是否有媒体在播放。 */
    fun isPlaying(context: Context): Boolean = snapshot(context).playing
}
