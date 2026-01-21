package com.eggreader.app.help.source

import com.eggreader.app.constant.SourceType
import com.eggreader.app.data.entities.BaseSource
import com.eggreader.app.data.entities.BookSource
import com.eggreader.app.data.entities.RssSource
import com.eggreader.app.model.SharedJsScope
import org.mozilla.javascript.Scriptable
import kotlin.coroutines.CoroutineContext

fun BaseSource.getShareScope(coroutineContext: CoroutineContext? = null): Scriptable? {
    return SharedJsScope.getScope(jsLib, coroutineContext)
}

fun BaseSource.getSourceType(): Int {
    return when (this) {
        is BookSource -> SourceType.book
        is RssSource -> SourceType.rss
        else -> error("unknown source type: ${this::class.simpleName}.")
    }
}

