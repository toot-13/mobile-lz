package com.dylr.recorder.core.resolve

/**
 * 从任意用户输入（完整链接 / 分享文案 / 纯房间号）中提取抖音直播 web_rid。
 * 短链本身不在这里解析，只做标记，由 DefaultLiveResolver 用 OkHttp 跟随重定向。
 */
object RoomIdParser {

    private val WEB_RID_PARAM = Regex("""web_rid=(\d{3,})""", RegexOption.IGNORE_CASE)
    private val LIVE_ROOM_URL = Regex("""live\.douyin\.com/(\d{3,})""", RegexOption.IGNORE_CASE)
    private val SHORT_LINK = Regex("""v\.douyin\.com/[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)
    private val PURE_ROOM_ID = Regex("""\d{3,}""")
    private val DIGIT_RUN = Regex("""\d{5,}""")

    /** 只有在出现直播上下文关键词时，才允许退回到“文本里的长数字”这种猜测。 */
    private val CONTEXT_HINT = Regex("""douyin|房间号|live""", RegexOption.IGNORE_CASE)

    fun parse(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        WEB_RID_PARAM.find(text)?.let { return it.groupValues[1] }
        LIVE_ROOM_URL.find(text)?.let { return it.groupValues[1] }
        if (isShortLink(text)) return null
        val compact = text.filterNot { it.isWhitespace() }
        if (PURE_ROOM_ID.matches(compact)) return compact
        if (!CONTEXT_HINT.containsMatchIn(text)) return null
        // 兜底猜测：取上下文里最长的一段连续数字，避免把时间戳 / 视频 id 当房间号
        return DIGIT_RUN.findAll(text).map { it.value }.maxByOrNull { it.length }
    }

    fun isShortLink(input: String): Boolean = SHORT_LINK.containsMatchIn(input)
}
