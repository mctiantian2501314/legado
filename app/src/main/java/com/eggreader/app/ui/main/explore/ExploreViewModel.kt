package com.eggreader.app.ui.main.explore

import android.app.Application
import com.eggreader.app.base.BaseViewModel
import com.eggreader.app.data.appDb
import com.eggreader.app.data.entities.BookSourcePart
import com.eggreader.app.help.config.SourceConfig
import com.eggreader.app.help.source.SourceHelp

class ExploreViewModel(application: Application) : BaseViewModel(application) {

    fun topSource(bookSource: BookSourcePart) {
        execute {
            val minXh = appDb.bookSourceDao.minOrder
            bookSource.customOrder = minXh - 1
            appDb.bookSourceDao.upOrder(bookSource)
        }
    }

    fun deleteSource(source: BookSourcePart) {
        execute {
            SourceHelp.deleteBookSource(source.bookSourceUrl)
        }
    }

}
