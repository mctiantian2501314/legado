package com.eggreader.app.model

import com.eggreader.app.constant.AppConst
import com.eggreader.app.data.appDb
import com.eggreader.app.data.entities.BookSource
import com.eggreader.app.data.entities.ReplaceRule
import com.eggreader.app.data.entities.RssSource
import com.eggreader.app.data.entities.RuleSub
import com.eggreader.app.exception.NoStackTraceException
import com.eggreader.app.help.book.ContentProcessor
import com.eggreader.app.help.http.decompressed
import com.eggreader.app.help.http.newCallResponseBody
import com.eggreader.app.help.http.okHttpClient
import com.eggreader.app.help.source.SourceHelp
import com.eggreader.app.utils.GSON
import com.eggreader.app.utils.fromJsonArray
import java.util.concurrent.ConcurrentHashMap

object RuleUpdate {
    val cacheBookSourceMap = ConcurrentHashMap<String, List<BookSource>>()
    val cacheRssSourceMap = ConcurrentHashMap<String, List<RssSource>>()
    val cacheReplaceRuleMap = ConcurrentHashMap<String, List<ReplaceRule>>()

    suspend fun cacheSource(ruleSub: RuleSub): Boolean {
        val url = ruleSub.url
        val type = ruleSub.type
        val silentUpdate = ruleSub.silentUpdate
        val update = ruleSub.update
        val updateInterval = ruleSub.updateInterval
        if (update + updateInterval * 3600 * 1000L > System.currentTimeMillis()) {
            return false
        } else {
            ruleSub.update = System.currentTimeMillis()
            appDb.ruleSubDao.update(ruleSub)
        }
        var upRules = false
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use {
            when (type) {
                0 -> GSON.fromJsonArray<BookSource>(it).getOrThrow().let { lists ->
                    val source = lists.firstOrNull() ?: return@let
                    if (source.bookSourceUrl.isEmpty()) {
                        throw NoStackTraceException("不是书源")
                    }
                    lists.forEach { list ->
                        val localSource = appDb.bookSourceDao.getBookSourcePart(list.bookSourceUrl)
                        if (localSource == null || localSource.lastUpdateTime < list.lastUpdateTime) {
                            if (silentUpdate) {
                                if (localSource != null) {
                                    list.bookSourceGroup = localSource.bookSourceGroup
                                }
                                SourceHelp.insertBookSource(list)
                                upRules = true
                            }
                            else {
                                cacheBookSourceMap.put(url, lists)
                                return true
                            }
                        }
                    }
                }
                1 -> GSON.fromJsonArray<RssSource>(it).getOrThrow().let { lists ->
                    val source = lists.firstOrNull() ?: return@let
                    if (source.sourceUrl.isEmpty()) {
                        throw NoStackTraceException("不是订阅源")
                    }
                    lists.forEach { list ->
                        val localSource = appDb.rssSourceDao.getByKey(list.sourceUrl)
                        if (localSource == null || localSource.lastUpdateTime < list.lastUpdateTime) {
                            if (silentUpdate) {
                                if (localSource != null) {
                                    list.sourceGroup = localSource.sourceGroup
                                }
                                SourceHelp.insertRssSource(list)
                            }
                            else {
                                cacheRssSourceMap.put(url, lists)
                                return true
                            }
                        }
                    }
                }
                2 -> GSON.fromJsonArray<ReplaceRule>(it).getOrThrow().let { lists ->
                    lists.forEach { list ->
                        val oldRule = appDb.replaceRuleDao.findById(list.id)
                        if (oldRule == null || list.pattern != oldRule.pattern || list.replacement != oldRule.replacement) {
                            if (silentUpdate) {
                                appDb.replaceRuleDao.insert(list)
                                upRules = true
                            }
                            else {
                                cacheReplaceRuleMap.put(url, lists)
                                return true
                            }
                        }
                    }
                }
            }
            if (upRules) {
                ContentProcessor.upReplaceRules()
            }
        }
        return false
    }
}
