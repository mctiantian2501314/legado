@file:Suppress("unused")

package com.eggreader.app.help.book

import com.eggreader.app.data.entities.BookChapter
import com.eggreader.app.help.RuleBigDataHelp.getDanmakuFile

fun BookChapter.getDanmaku(): Any? { //读取弹幕数据
    return variableMap["danmaku"] ?: getDanmakuFile(bookUrl, url)
}
