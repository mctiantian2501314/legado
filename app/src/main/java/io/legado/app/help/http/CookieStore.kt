@file:Suppress("unused")

package io.legado.app.help.http

import android.text.TextUtils
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.equalsRegex
import io.legado.app.constant.AppPattern.semicolonRegex
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cookie
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieManager.getCookieNoSession
import io.legado.app.help.http.CookieManager.mergeCookiesToMap
import io.legado.app.help.http.api.CookieManagerInterface
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.removeCookie

@Keep
object CookieStore : CookieManagerInterface {

    // Cookie污染检测开关（默认开启）
    private var contaminationCheckEnabled: Boolean = true

    /**
     * 保存cookie到数据库，会自动识别url的二级域名
     */
    override fun setCookie(url: String, cookie: String?) {
        try {
            val domain = NetworkUtils.getSubDomain(url)
            CacheManager.putMemory("${domain}_cookie", cookie ?: "")
            val cookieBean = Cookie(domain, cookie ?: "")
            appDb.cookieDao.insert(cookieBean)

            logCookieActivity("STORE", domain, cookie)
        } catch (e: Exception) {
            AppLog.put("保存Cookie失败\n$e", e)
        }
    }

    /**
     * 设置WebCookie
     */
    fun setWebCookie(url: String, cookie: String) {
        try {
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            val cookies = cookie.split("; ").filter { it.isNotBlank() }
            val cookieManager = android.webkit.CookieManager.getInstance()

            cookies.forEach { cookiePair ->
                // 简单的清理，移除换行符
                val sanitizedCookie = cookiePair.replace("\n", "").replace("\r", "")
                if (sanitizedCookie.isNotBlank()) {
                    cookieManager.setCookie(baseUrl, sanitizedCookie)
                }
            }

            logCookieActivity("SET_WEB", NetworkUtils.getSubDomain(url), cookie)
        } catch (e: Exception) {
            AppLog.put("设置WebCookie失败\n$e", e)
        }
    }

    /**
     * 替换cookie，确保正确的合并
     */
    override fun replaceCookie(url: String, cookie: String) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(cookie)) {
            return
        }
        val domain = NetworkUtils.getSubDomain(url)
        val oldCookie = getCookieNoSession(url)
        if (TextUtils.isEmpty(oldCookie)) {
            setCookie(url, cookie)
        } else {
            // 合并新旧Cookie
            val oldCookieMap = cookieToMap(oldCookie)
            val newCookieMap = cookieToMap(cookie)

            // 合并规则：新Cookie覆盖旧Cookie
            newCookieMap.forEach { (key, value) ->
                // 跳过属性标记
                if (!key.startsWith("__attr_")) {
                    oldCookieMap[key] = value
                }
            }

            // 合并属性
            newCookieMap.filterKeys { it.startsWith("__attr_") }
                .forEach { (key, value) ->
                    oldCookieMap[key] = value
                }

            val newCookie = mapToCookie(oldCookieMap)
            setCookie(url, newCookie)

            logCookieActivity("REPLACE", domain, newCookie)
        }
    }

    /**
     * 获取url所属的二级域名的cookie
     */
    override fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)

        // 获取内存中的cookie
        val cookie = getCookieNoSession(url)
        val sessionCookie = CookieManager.getSessionCookie(domain)

        // 合并cookie
        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)

        // 过滤污染的Cookie（只移除 Hm_lvt_ 和 Hm_lpvt_）
        val validCookieMap = mutableMapOf<String, String>()
        cookieMap.forEach { (key, value) ->
            // 如果是污染的Cookie，则跳过
            if (isContaminatedCookie(key)) {
                logCookieActivity("REMOVE_CONTAMINATED", domain, "$key=$value")
            } else {
                validCookieMap[key] = value
            }
        }

        var ck = mapToCookie(validCookieMap) ?: ""

        // 如果Cookie太长，尝试移除一些非必需的Cookie
        while (ck.length > 4096 && validCookieMap.size > 1) {
            // 尝试移除一个非sessionid的Cookie
            val removeKey = validCookieMap.keys.find {
                it != "sessionid" && it != "SessionID" && !it.startsWith("__attr_")
            } ?: break

            CookieManager.removeCookie(url, removeKey)
            validCookieMap.remove(removeKey)
            ck = mapToCookie(validCookieMap) ?: ""
        }

        logCookieActivity("GET", domain, if (ck.length > 100) "${ck.substring(0, 100)}..." else ck)
        return ck
    }

    /**
     * 获取指定key的cookie值
     */
    fun getKey(url: String, key: String): String {
        val cookie = getCookie(url)
        val sessionCookie = CookieManager.getSessionCookie(url)
        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)
        return cookieMap[key] ?: ""
    }

    /**
     * 删除指定url的cookie
     */
    override fun removeCookie(url: String) {
        val domain = NetworkUtils.getSubDomain(url)
        appDb.cookieDao.delete(domain)
        CacheManager.deleteMemory("${domain}_cookie")
        CacheManager.deleteMemory("${domain}_session_cookie")
        android.webkit.CookieManager.getInstance().removeCookie(url)

        logCookieActivity("REMOVE", domain, null)
    }

    /**
     * 将cookie字符串转换为映射
     */
    override fun cookieToMap(cookie: String): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        if (cookie.isBlank()) {
            return cookieMap
        }

        val pairArray = cookie.split(semicolonRegex).filter { it.isNotBlank() }
        for (pair in pairArray) {
            val trimmedPair = pair.trim()

            // 处理Cookie属性（Secure, HttpOnly, SameSite等）
            if (isCookieAttribute(trimmedPair)) {
                cookieMap["__attr_$trimmedPair"] = ""
                continue
            }

            // 分割key=value
            val pairs = trimmedPair.split(equalsRegex, 2)
            if (pairs.size != 2) {
                continue
            }

            val key = pairs[0].trim()
            val value = pairs[1].trim()

            // 只检查是否是污染的Cookie
            if (isContaminatedCookie(key)) {
                logCookieActivity("SKIP_CONTAMINATED", "PARSING", "$key=$value")
                continue
            }

            cookieMap[key] = value
        }

        return cookieMap
    }

    /**
     * 将映射转换为cookie字符串
     */
    override fun mapToCookie(cookieMap: Map<String, String>?): String? {
        if (cookieMap.isNullOrEmpty()) {
            return null
        }

        val builder = StringBuilder()
        val regularCookies = mutableListOf<String>()
        val attributes = mutableListOf<String>()

        cookieMap.forEach { (key, value) ->
            if (key.startsWith("__attr_")) {
                attributes.add(key.substring(7))
            } else {
                regularCookies.add("$key=$value")
            }
        }

        // 添加常规Cookie
        regularCookies.forEachIndexed { index, cookie ->
            if (index > 0) builder.append("; ")
            builder.append(cookie)
        }

        // 添加属性
        attributes.forEach { attribute ->
            builder.append("; ").append(attribute)
        }

        return builder.toString()
    }

    /**
     * 清除所有cookie
     */
    fun clear() {
        appDb.cookieDao.deleteOkHttp()
        logCookieActivity("CLEAR", "ALL", null)
    }

    /**
     * 禁用Cookie污染检测
     */
    fun disableContaminationCheck() {
        contaminationCheckEnabled = false
        logCookieActivity("SETTING", "ALL", "Contamination check disabled")
    }

    /**
     * 启用Cookie污染检测
     */
    fun enableContaminationCheck() {
        contaminationCheckEnabled = true
        logCookieActivity("SETTING", "ALL", "Contamination check enabled")
    }

    /**
     * 检测是否是污染的Cookie

     */
    private fun isContaminatedCookie(key: String): Boolean {
        if (!contaminationCheckEnabled) {
            return false
        }


        return key.startsWith("Hm_lvt_") || key.startsWith("Hm_lpvt_") || key.startsWith("Hm_tf_")
    }

    /**
     * 判断是否是Cookie属性
     */
    private fun isCookieAttribute(text: String): Boolean {
        return text.equals("Secure", ignoreCase = true) ||
                text.equals("HttpOnly", ignoreCase = true) ||
                text.startsWith("SameSite=", ignoreCase = true) ||
                text.startsWith("Path=", ignoreCase = true) ||
                text.startsWith("Domain=", ignoreCase = true) ||
                text.startsWith("Max-Age=", ignoreCase = true) ||
                text.startsWith("Expires=", ignoreCase = true)
    }

    /**
     * 记录cookie相关活动
     */
    private fun logCookieActivity(action: String, domain: String, cookie: String?) {
        val cookieSummary = cookie?.let {
            if (it.length > 100) "${it.substring(0, 100)}..." else it
        } ?: "null"
        AppLog.putDebug("CookieActivity: $action | Domain: $domain | Cookie: $cookieSummary")
    }
}