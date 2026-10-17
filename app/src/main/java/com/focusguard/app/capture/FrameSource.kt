package com.focusguard.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.util.Log
import com.focusguard.app.access.GuardAccessibilityService
import java.io.ByteArrayOutputStream

private const val TAG = "FrameSource"

/** 送入视觉模型 / 感知哈希的目标宽度，与 [ScreenCapturer] 保持一致。 */
private const val FRAME_TARGET_WIDTH = 640

/** JPEG 质量，与 [ScreenCapturer] 保持一致。 */
private const val FRAME_JPEG_QUALITY = 55

/**
 * 画面来源抽象。
 *
 * 为什么要有这一层：系统「屏幕共享」（MediaProjection）会在息屏、锁屏、
 * 切换用户或系统回收授权时失效，而恢复授权必须由用户手动点确认，
 * 于是出现了"一息屏守护就瞎了"的问题。
 *
 * 把"取画面"从守护循环里抽出来之后：
 *  - 投影断了只是少一条采集通道，守护循环照常跑；
 *  - 可以按优先级挂上多条通道（无障碍截图 / 屏幕共享）；
 *  - 全部不可用时上层自动退回到文字 + 应用分类 + 媒体播放的事件层判定。
 */
interface FrameSource {

    /** 通道显示名，用于日志与通知里标注"现在靠什么在看"。 */
    val name: String

    /** 当前是否可用（例如无障碍服务已被系统回收就不可用）。 */
    val available: Boolean

    /** 采集一帧，失败返回 null。调用方必须负责 [CaptureResult.recycle]。 */
    fun grab(): CaptureResult?

    /** 释放底层资源。 */
    fun release() {}
}

/**
 * 通道一：系统屏幕共享（MediaProjection）。
 *
 * 画面质量最好、也能拿到完整界面，但依赖用户授权，
 * 且授权被系统回收后必须重新走一遍确认弹窗。
 */
class MediaProjectionFrameSource(
    context: Context,
    private val projection: MediaProjection
) : FrameSource {

    private val capturer = ScreenCapturer(context)

    override val name: String = "屏幕共享"

    override val available: Boolean get() = true

    override fun grab(): CaptureResult? = capturer.capture(projection)

    override fun release() {
        try {
            capturer.close()
        } catch (e: Exception) {
            Log.w(TAG, "释放截屏器失败：${e.message}")
        }
    }
}

/**
 * 通道二：无障碍截图（API 30+）。
 *
 * 不需要任何授权弹窗，也不会因为息屏而"被系统结束共享"——
 * 服务活着就能随时取一帧，是比屏幕共享更适合长期守护的通道。
 *
 * 代价：分辨率是整屏原始尺寸，需要自己缩放到 640 宽再压缩，
 * 否则既费内存也费视觉模型的 token。
 */
class AccessibilityFrameSource : FrameSource {

    override val name: String = "无障碍截图"

    override val available: Boolean get() = GuardAccessibilityService.screenshotCapable

    override fun grab(): CaptureResult? {
        val full = GuardAccessibilityService.captureScreenSync() ?: return null
        var scaled: Bitmap? = null
        try {
            val bmp = scaleToWidth(full, FRAME_TARGET_WIDTH)
            scaled = bmp
            val bytes = ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, out)
                out.toByteArray()
            }
            return CaptureResult(bytes, bmp)
        } catch (e: Exception) {
            Log.w(TAG, "无障碍帧编码失败：${e.message}")
            scaled?.recycle()
            return null
        } finally {
            // 缩放产生的是新位图，原图可以立刻回收省内存；
            // 极端情况下系统直接返回原图则交给 CaptureResult 负责回收。
            if (scaled !== full) {
                try {
                    full.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 等比缩放到目标宽度。屏幕共享通道由系统缩放，这里必须自己来。 */
    private fun scaleToWidth(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width <= targetWidth) return src
        val height = (src.height.toLong() * targetWidth / src.width).toInt()
        // 部分设备的硬件缓冲对奇数尺寸不友好，统一取偶数
        val evenHeight = if (height % 2 == 0) height else height + 1
        return Bitmap.createScaledBitmap(src, targetWidth, evenHeight.coerceAtLeast(2), true)
    }
}
