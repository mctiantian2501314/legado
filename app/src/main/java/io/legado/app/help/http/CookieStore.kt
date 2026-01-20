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
import io.legado.app.utils.splitNotBlank
import java.util.concurrent.TimeUnit

@Keep
object CookieStore : CookieManagerInterface {

    // Cookie namespace constants
    private const val NAMESPACE_AUTH = "auth_"
    private const val NAMESPACE_SESSION = "session_"
    private const val NAMESPACE_FEATURE = "feature_"
    private const val NAMESPACE_THIRD_PARTY = "third_"
    
    // Cookie attribute constants
    private const val ATTR_SECURE = "Secure"
    private const val ATTR_HTTP_ONLY = "HttpOnly"
    private const val ATTR_SAME_SITE_STRICT = "SameSite=Strict"
    private const val ATTR_SAME_SITE_LAX = "SameSite=Lax"
    private const val ATTR_SAME_SITE_NONE = "SameSite=None"
    
    // Contaminated cookie patterns
    private val CONTAMINATED_COOKIE_PATTERNS = listOf(
        Regex("^Hm_tf_.*")
    )
    
    // Cookie whitelist for essential login credentials
    private var COOKIE_WHITELIST = mutableListOf(
        "sessionid",
        "auth_token",
        "login_token",
        "fanqie_*",  // Support wildcard matching
        "Hm_lvt_2667d29c8e792e6fa9182c20a3013175",
        "Hm_lpvt_2667d29c8e792e6fa9182c20a3013175"
    )
    
    // Contamination tolerance threshold (in milliseconds)
    private var contaminationTolerance: Long = TimeUnit.MINUTES.toMillis(5)
    
    // Contamination check enabled flag
    private var contaminationCheckEnabled: Boolean = true

    /**
     * 保存cookie到数据库，会自动识别url的二级域名
     */
    override fun setCookie(url: String, cookie: String?) {
        try {
            val domain = NetworkUtils.getSubDomain(url)
            // Store cookies per domain for proper isolation
            CacheManager.putMemory("${domain}_cookie", cookie ?: "")
            val cookieBean = Cookie(domain, cookie ?: "")
            appDb.cookieDao.insert(cookieBean)
            
            // Log cookie storage activity
            logCookieActivity("STORE", domain, cookie)
        } catch (e: Exception) {
            AppLog.put("保存Cookie失败\n$e", e)
        }
    }

    /**
     * 设置WebCookie，支持结构化命名和属性管理
     */
    fun setWebCookie(url: String, cookie: String) {
        try {
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            val cookies = cookie.splitNotBlank("; ")
            val cookieManager = android.webkit.CookieManager.getInstance()
            
            cookies.forEach { cookiePair ->
                // Validate and sanitize cookie before setting
                val sanitizedCookie = sanitizeCookie(cookiePair)
                if (sanitizedCookie.isNotBlank()) {
                    cookieManager.setCookie(baseUrl, sanitizedCookie)
                }
            }
            
            // Log web cookie setting activity
            logCookieActivity("SET_WEB", NetworkUtils.getSubDomain(url), cookie)
        } catch (e: Exception) {
            AppLog.put("设置WebCookie失败\n$e", e)
        }
    }

    /**
     * 替换cookie，确保正确的合并和隔离
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
            // Parse and merge cookies carefully to avoid pollution
            val oldCookieMap = cookieToMap(oldCookie)
            val newCookieMap = cookieToMap(cookie)
            
            // Only replace existing cookies with the same name
            // This prevents unintended cookie pollution
            newCookieMap.forEach { (key, value) ->
                if (!key.startsWith("__attr_")) {
                    oldCookieMap[key] = value
                }
            }
            
            // Preserve attributes from both old and new cookies
            newCookieMap.filterKeys { it.startsWith("__attr_") }
                .forEach { (key, value) ->
                    oldCookieMap[key] = value
                }
            
            val newCookie = mapToCookie(oldCookieMap)
            setCookie(url, newCookie)
            
            // Log cookie replacement activity
            logCookieActivity("REPLACE", domain, newCookie)
        }
    }

    /**
     * 获取url所属的二级域名的cookie
     */
    override fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)

        val cookie = getCookieNoSession(url)
        val sessionCookie = CookieManager.getSessionCookie(domain)

        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)

        // Validate, clean up, and filter contaminated cookies
        val validCookieMap = mutableMapOf<String, String>()
        cookieMap.forEach { (key, value) ->
            if (key.startsWith("__attr_")) {
                // Validate attributes
                validCookieMap[key] = value
            } else if (isValidCookieKey(key) && isValidCookieValue(value) && !isContaminatedCookie(key, value)) {
                validCookieMap[key] = value
            } else if (isContaminatedCookie(key, value)) {
                // Log contaminated cookie detection during retrieval
                logCookieActivity("FILTER_CONTAMINATED", domain, "Contaminated cookie filtered: $key=$value")
            }
        }

        var ck = mapToCookie(validCookieMap) ?: ""
        while (ck.length > 4096) {
            // Remove non-essential cookies first if possible
            val removeKey = validCookieMap.keys.find { 
                !it.startsWith("__attr_") && !it.startsWith(NAMESPACE_AUTH) && !it.startsWith(NAMESPACE_SESSION)
            } ?: validCookieMap.keys.random()
            CookieManager.removeCookie(url, removeKey.replace("__attr_", ""))
            validCookieMap.remove(removeKey)
            ck = mapToCookie(validCookieMap) ?: ""
        }
        
        // Log cookie retrieval activity
        logCookieActivity("GET", domain, ck)
        
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
        
        // Log cookie removal activity
        logCookieActivity("REMOVE", domain, null)
    }

    /**
     * 为不同类型的cookie生成结构化名称
     */
    fun generateStructuredCookieName(type: CookieType, purpose: String, uniqueId: String = ""): String {
        val namespace = when (type) {
            CookieType.AUTHENTICATION -> NAMESPACE_AUTH
            CookieType.SESSION -> NAMESPACE_SESSION
            CookieType.FEATURE -> NAMESPACE_FEATURE
            CookieType.THIRD_PARTY -> NAMESPACE_THIRD_PARTY
        }
        
        return if (uniqueId.isBlank()) {
            "${namespace}${purpose}"
        } else {
            "${namespace}${purpose}_${uniqueId}"
        }
    }

    /**
     * 设置带有适当属性的安全cookie
     */
    fun setSecureCookie(url: String, name: String, value: String, cookieType: CookieType, 
                       sameSite: SameSitePolicy, path: String = "/", maxAge: Int? = null) {
        try {
            val domain = NetworkUtils.getSubDomain(url)
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            
            // Build cookie with appropriate attributes
            val cookieBuilder = StringBuilder()
            cookieBuilder.append("$name=$value")
            cookieBuilder.append("; Path=$path")
            cookieBuilder.append("; Domain=$domain")
            
            // Add SameSite attribute
            cookieBuilder.append("; ").append(getSameSiteAttribute(sameSite))
            
            // Add Secure attribute for all sensitive cookies
            if (cookieType == CookieType.AUTHENTICATION || cookieType == CookieType.SESSION) {
                cookieBuilder.append("; $ATTR_SECURE")
                cookieBuilder.append("; $ATTR_HTTP_ONLY")
            }
            
            // Add Max-Age if specified
            maxAge?.let {
                cookieBuilder.append("; Max-Age=$it")
            }
            
            val cookie = cookieBuilder.toString()
            setCookie(url, cookie)
            
            // Also set for web view if needed
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setCookie(baseUrl, cookie)
            
            // Log secure cookie setting
            logCookieActivity("SET_SECURE", domain, cookie)
        } catch (e: Exception) {
            AppLog.put("设置安全Cookie失败\n$e", e)
        }
    }

    /**
     * 验证cookie键是否有效
     */
    private fun isValidCookieKey(key: String): Boolean {
        if (key.isBlank() || key.length > 100) {
            return false
        }
        // Enhanced validation for cookie key characters
        return key.matches(Regex("^[a-zA-Z0-9!#$%&'*+-.^_`|~]+$"))
    }

    /**
     * 验证cookie值是否有效
     */
    private fun isValidCookieValue(value: String): Boolean {
        if (value.length > 1000) {
            return false
        }
        // Enhanced validation for cookie value characters
        return !value.contains("\n") && !value.contains("\r") && !value.contains("; ")
    }
    
    /**
     * 检测cookie是否被污染
     */
    private fun isContaminatedCookie(key: String, value: String = ""): Boolean {
        // Skip check if contamination check is disabled
        if (!contaminationCheckEnabled) {
            return false
        }
        
        // Check if cookie is in whitelist
        if (isInWhitelist(key)) {
            return false
        }
        
        // Check for contaminated patterns
        if (CONTAMINATED_COOKIE_PATTERNS.any { pattern -> pattern.matches(key) }) {
            return true
        }
        
        // Special handling for Hm_* cookies with timestamp validation
        if (key.startsWith("Hm_lvt_") || key.startsWith("Hm_lpvt_")) {
            return isHmCookieContaminated(key, value)
        }
        
        return false
    }
    
    /**
     * Check if cookie is in whitelist
     */
    private fun isInWhitelist(key: String): Boolean {
        return COOKIE_WHITELIST.any { whitelistPattern ->
            if (whitelistPattern.endsWith("*")) {
                // Handle wildcard patterns
                key.startsWith(whitelistPattern.substring(0, whitelistPattern.length - 1))
            } else {
                key == whitelistPattern
            }
        }
    }
    
    /**
     * Check if Hm_* cookie is contaminated based on timestamp difference
     */
    private fun isHmCookieContaminated(key: String, value: String): Boolean {
        try {
            val currentTime = System.currentTimeMillis() / 1000 // Convert to seconds
            
            if (key.startsWith("Hm_lvt_")) {
                // Hm_lvt_ contains multiple timestamps separated by commas
                val timestamps = value.split(",")
                for (timestampStr in timestamps) {
                    val timestamp = timestampStr.toLongOrNull() ?: continue
                    val timeDiff = currentTime - timestamp
                    if (timeDiff < 0 || timeDiff * 1000 > contaminationTolerance) {
                        // Timestamp is in future or exceeds tolerance
                        return true
                    }
                }
            } else if (key.startsWith("Hm_lpvt_")) {
                // Hm_lpvt_ contains single timestamp
                val timestamp = value.toLongOrNull() ?: return true
                val timeDiff = currentTime - timestamp
                if (timeDiff < 0 || timeDiff * 1000 > contaminationTolerance) {
                    // Timestamp is in future or exceeds tolerance
                    return true
                }
            }
        } catch (e: Exception) {
            // If parsing fails, assume not contaminated
        }
        
        return false
    }
    
    /**
     * 过滤污染的cookie
     */
    private fun filterContaminatedCookies(cookieMap: Map<String, String>): MutableMap<String, String> {
        val filteredMap = mutableMapOf<String, String>()
        
        cookieMap.forEach { (key, value) ->
            if (!key.startsWith("__attr_") && isContaminatedCookie(key, value)) {
                // Log contaminated cookie detection
                logCookieActivity("FILTER_CONTAMINATED", "ALL", "Contaminated cookie detected: $key=$value")
            } else {
                filteredMap[key] = value
            }
        }
        
        return filteredMap
    }
    
    /**
     * 清理所有污染的cookie
     */
    fun cleanContaminatedCookies() {
        try {
            // Get all cookies from database
            val allCookies = appDb.cookieDao.getAll()
            var cleanedCount = 0
            
            allCookies.forEach { cookie ->
                val cookieMap = cookieToMap(cookie.cookie)
                val filteredMap = filterContaminatedCookies(cookieMap)
                
                if (filteredMap.size != cookieMap.size) {
                    // Update cookie with filtered version
                    val cleanedCookie = mapToCookie(filteredMap)
                    val updatedCookie = cookie.copy(cookie = cleanedCookie ?: "")
                    appDb.cookieDao.insert(updatedCookie)
                    
                    // Also update in memory cache
                    CacheManager.deleteMemory("${cookie.url}_cookie")
                    cleanedCount++
                }
            }
            
            // Log cleanup activity
            logCookieActivity("CLEAN_CONTAMINATED", "ALL", "Cleaned $cleanedCount contaminated cookies")
        } catch (e: Exception) {
            AppLog.put("清理污染Cookie失败\n$e", e)
        }
    }

    /**
     * 清理和验证cookie
     */
    private fun sanitizeCookie(cookie: String): String {
        // Remove any potentially harmful content
        var sanitized = cookie.replace("\n", "").replace("\r", "")
        
        // Validate cookie length
        if (sanitized.length > 4096) {
            return ""
        }
        
        return sanitized
    }

    /**
     * 将cookie字符串转换为映射
     */
    override fun cookieToMap(cookie: String): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        if (cookie.isBlank() || cookie.length > 4096) {
            // Reject overly large cookies
            return cookieMap
        }
        val pairArray = cookie.split(semicolonRegex).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (pair in pairArray) {
            val trimmedPair = pair.trim()
            // Handle attributes without values (Secure, HttpOnly, etc.)
            if (trimmedPair.equals(ATTR_SECURE, ignoreCase = true) || 
                trimmedPair.equals(ATTR_HTTP_ONLY, ignoreCase = true) ||
                trimmedPair.startsWith("SameSite=", ignoreCase = true) ||
                trimmedPair.startsWith("Path=", ignoreCase = true) ||
                trimmedPair.startsWith("Domain=", ignoreCase = true) ||
                trimmedPair.startsWith("Max-Age=", ignoreCase = true)) {
                // Store attributes as special keys with their values
                cookieMap["__attr_$trimmedPair"] = ""
                continue
            }
            val pairs = trimmedPair.split(equalsRegex, 2).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (pairs.size <= 1) {
                continue
            }
            val key = pairs[0].trim { it <= ' ' }
            val value = pairs[1].trim { it <= ' ' }
            
            // Validate cookie key and value
            if (!isValidCookieKey(key) || !isValidCookieValue(value)) {
                continue
            }
            
            // Check for contaminated cookies
            if (isContaminatedCookie(key, value)) {
                // Log contaminated cookie detection
                logCookieActivity("DETECT_CONTAMINATED", "ALL", "Contaminated cookie detected: $key=$value")
                continue
            }
            
            if (value.isNotBlank() || value == "null") {
                cookieMap[key] = value
            }
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
        
        cookieMap.keys.forEach { key ->
            if (key.startsWith("__attr_")) {
                // Extract attribute from special key
                attributes.add(key.substring(7))
            } else {
                regularCookies.add("$key=${cookieMap[key]}")
            }
        }
        
        // Add regular cookies first
        regularCookies.forEachIndexed { index, cookie ->
            if (index > 0) builder.append("; ")
            builder.append(cookie)
        }
        
        // Add attributes
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
        
        // Log cookie clearing activity
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
     * 设置Cookie污染检测的时间容忍度阈值
     */
    fun setContaminationTolerance(toleranceMs: Long) {
        contaminationTolerance = toleranceMs
        logCookieActivity("SETTING", "ALL", "Contamination tolerance set to ${toleranceMs}ms")
    }
    
    /**
     * 设置Cookie白名单
     */
    fun setCookieWhitelist(whitelist: List<String>) {
        COOKIE_WHITELIST.clear()
        COOKIE_WHITELIST.addAll(whitelist)
        logCookieActivity("SETTING", "ALL", "Cookie whitelist updated: ${whitelist.size} entries")
    }
    
    /**
     * 仅清理污染的Cookie，保留合法Cookie
     */
    fun removeOnlyContaminatedCookies() {
        try {
            // Get all cookies from database
            val allCookies = appDb.cookieDao.getAll()
            var cleanedCount = 0
            
            allCookies.forEach { cookie ->
                val cookieMap = cookieToMap(cookie.cookie)
                val filteredMap = filterContaminatedCookies(cookieMap)
                
                if (filteredMap.size != cookieMap.size) {
                    // Update cookie with filtered version
                    val cleanedCookie = mapToCookie(filteredMap)
                    val updatedCookie = cookie.copy(cookie = cleanedCookie ?: "")
                    appDb.cookieDao.insert(updatedCookie)
                    
                    // Also update in memory cache
                    CacheManager.deleteMemory("${cookie.url}_cookie")
                    cleanedCount++
                }
            }
            
            // Log cleanup activity
            logCookieActivity("CLEAN_CONTAMINATED", "ALL", "Cleaned $cleanedCount contaminated cookies")
        } catch (e: Exception) {
            AppLog.put("清理污染Cookie失败\n$e", e)
        }
    }

    /**
     * 获取SameSite属性值
     */
    private fun getSameSiteAttribute(policy: SameSitePolicy): String {
        return when (policy) {
            SameSitePolicy.STRICT -> ATTR_SAME_SITE_STRICT
            SameSitePolicy.LAX -> ATTR_SAME_SITE_LAX
            SameSitePolicy.NONE -> ATTR_SAME_SITE_NONE
        }
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

    /**
     * Cookie类型枚举
     */
    enum class CookieType {
        AUTHENTICATION,
        SESSION,
        FEATURE,
        THIRD_PARTY
    }

    /**
     * SameSite策略枚举
     */
    enum class SameSitePolicy {
        STRICT,
        LAX,
        NONE
    }

}