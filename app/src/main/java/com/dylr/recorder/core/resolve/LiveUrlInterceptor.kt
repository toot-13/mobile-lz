package com.dylr.recorder.core.resolve

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 只观察、不接管：shouldInterceptRequest 中把 http(s) 流地址交给 [onHit]，
 * 始终返回 null，让 WebView 按原生方式加载（不消费 body，只借用 url 做识别）。
 * [onPageReady] 在页面级导航开始/结束时回调，用于注入 JS 钩子（脚本自带幂等标记）。
 */
class LiveUrlInterceptor(
    private val onHit: (String) -> Unit = {},
    private val onPageReady: ((WebView?) -> Unit)? = null
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (url != null && isCandidate(url)) {
            try {
                onHit(url)
            } catch (_: Throwable) {
                // 观察者异常不得影响页面加载
            }
        }
        return null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        notifyPageReady(view)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        notifyPageReady(view)
    }

    private fun notifyPageReady(view: WebView?) {
        try {
            onPageReady?.invoke(view)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val FLV = ".flv"
        private const val HLS = ".m3u8"

        /** 容器优先级：flv 恒高于 m3u8（分值区间不重叠：flv 10-13，m3u8 5-8）。 */
        const val SCORE_FLV_BASE = 10
        const val SCORE_HLS_BASE = 5

        fun isHttpUrl(url: String): Boolean =
            url.startsWith("http://", true) || url.startsWith("https://", true)

        fun isFlv(url: String): Boolean = isHttpUrl(url) && url.contains(FLV, true)

        fun isHls(url: String): Boolean = isHttpUrl(url) && url.contains(HLS, true)

        /**
         * 同一容器内的清晰度档位：3 = 原画/蓝光（uhd / origin / full_hd / 1080），
         * 2 = 高清（_hd / hd1 / 720），1 = 无标记（居中），0 = 标清/低清（_sd / sd1 / _ld / 480）。
         */
        fun qualityRank(url: String): Int {
            val lower = url.lowercase()
            if (lower.contains("uhd") || lower.contains("origin") || lower.contains("full_hd") ||
                lower.contains("fullhd") || lower.contains("1080") || lower.contains("原画")
            ) {
                return 3
            }
            if (lower.contains("_hd") || lower.contains("hd1") || lower.contains("hd_") ||
                lower.contains("/hd/") || lower.contains("-hd") ||
                lower.contains("720") || lower.contains("高清")
            ) {
                return 2
            }
            if (lower.contains("_sd") || lower.contains("sd1") || lower.contains("sd2") ||
                lower.contains("_ld") || lower.contains("/sd/") || lower.contains("/ld/") ||
                lower.contains("-sd") || lower.contains("480") || lower.contains("标清")
            ) {
                return 0
            }
            return 1
        }

        /**
         * 0 = 非流地址；否则 = 容器基准分 + 清晰度档位。
         * 于是「flv 标清(10) > m3u8 原画(8)」，容器优先级不被清晰度破坏，容器内再按档位比高低。
         */
        fun score(url: String): Int {
            if (!isHttpUrl(url)) return 0
            val rank = qualityRank(url)
            return when {
                isFlv(url) -> SCORE_FLV_BASE + rank
                isHls(url) -> SCORE_HLS_BASE + rank
                else -> 0
            }
        }

        fun isCandidate(url: String): Boolean = score(url) > 0
    }
}
