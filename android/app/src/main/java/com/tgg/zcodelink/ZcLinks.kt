package com.tgg.zcodelink

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条 ZCode 手机远控连接地址。
 *
 * 只保存用户提供的原始 URL —— 它是连接凭据（含 hash 签名），本应用不会把它
 * 发往 zcode.z.ai 以外的任何地方，也不会写入日志。
 */
data class ZcLink(
    val url: String,
    val sid: String,
    val name: String,
    val mid: String,
    val t: Long?,
    val savedAt: Long,
)

object ZcLinks {

    private const val PREFS = "zcode_link"
    private const val KEY_LIST = "addresses"
    private const val KEY_LAST = "last_url"
    private const val MAX_SAVED = 20

    private val PATH_RE = Regex("^/remote/v\\d+/?$")
    private val URL_IN_TEXT_RE = Regex("https?://zcode\\.z\\.ai/remote/v\\d+\\?\\S+")

    sealed class ParseResult {
        data class Ok(val link: ZcLink) : ParseResult()
        data object Empty : ParseResult()
        data object NotZcode : ParseResult()
        data object MissingParams : ParseResult()
    }

    /** 校验并解析连接地址；不做任何网络请求。 */
    fun parse(raw: String): ParseResult {
        val text = raw.trim()
        if (text.isEmpty()) return ParseResult.Empty

        val uri = try {
            Uri.parse(text)
        } catch (_: Exception) {
            return ParseResult.NotZcode
        }

        val scheme = uri.scheme.orEmpty()
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        if (!scheme.equals("https", true) || !host.equals("zcode.z.ai", true) || !PATH_RE.matches(path)) {
            return ParseResult.NotZcode
        }

        val sid = uri.getQueryParameter("sid").orEmpty()
        val hash = uri.getQueryParameter("hash").orEmpty()
        if (sid.isEmpty() || hash.isEmpty()) return ParseResult.MissingParams

        return ParseResult.Ok(
            ZcLink(
                url = text,
                sid = sid,
                name = uri.getQueryParameter("name").orEmpty(),
                mid = uri.getQueryParameter("mid").orEmpty(),
                t = normalizeTimestamp(uri.getQueryParameter("t")),
                savedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 从任意分享文本里挑出第一条连接地址（聊天/备忘录里复制来的整段文字也能用）。 */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val direct = parse(text)
        if (direct is ParseResult.Ok) return direct.link.url
        return URL_IN_TEXT_RE.find(text)?.value?.let { candidate ->
            (parse(candidate) as? ParseResult.Ok)?.link?.url
        }
    }

    private fun normalizeTimestamp(raw: String?): Long? {
        if (raw.isNullOrEmpty() || !raw.all { it.isDigit() }) return null
        val value = raw.toLongOrNull() ?: return null
        return when {
            raw.length >= 12 -> value          // 毫秒
            raw.length == 10 -> value * 1000L  // 秒
            else -> value
        }
    }

    fun load(context: Context): MutableList<ZcLink> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                ZcLink(
                    url = o.optString("url"),
                    sid = o.optString("sid"),
                    name = o.optString("name"),
                    mid = o.optString("mid"),
                    t = if (o.has("t") && !o.isNull("t")) o.optLong("t") else null,
                    savedAt = o.optLong("savedAt"),
                )
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun add(context: Context, raw: String): MutableList<ZcLink> {
        val parsed = parse(raw)
        if (parsed !is ParseResult.Ok) return load(context)
        val list = load(context)
        list.removeAll { it.url == parsed.link.url }
        list.add(0, parsed.link)
        while (list.size > MAX_SAVED) list.removeAt(list.size - 1)
        save(context, list)
        setLastUrl(context, parsed.link.url)
        return list
    }

    fun remove(context: Context, url: String): MutableList<ZcLink> {
        val list = load(context)
        list.removeAll { it.url == url }
        save(context, list)
        if (lastUrl(context) == url) {
            val next = list.firstOrNull()?.url
            if (next != null) setLastUrl(context, next) else prefs(context).edit().remove(KEY_LAST).apply()
        }
        return list
    }

    private fun save(context: Context, list: List<ZcLink>) {
        val array = JSONArray()
        list.forEach { link ->
            array.put(
                JSONObject().apply {
                    put("url", link.url)
                    put("sid", link.sid)
                    put("name", link.name)
                    put("mid", link.mid)
                    if (link.t != null) put("t", link.t) else put("t", JSONObject.NULL)
                    put("savedAt", link.savedAt)
                }
            )
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    fun lastUrl(context: Context): String? = prefs(context).getString(KEY_LAST, null)

    fun setLastUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_LAST, url).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
