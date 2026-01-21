package com.eggreader.app.ui.rss.read

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.eggreader.app.constant.AppLog
import com.eggreader.app.constant.SourceType
import com.eggreader.app.data.appDb
import com.eggreader.app.data.entities.RssArticle
import com.eggreader.app.data.entities.RssReadRecord
import com.eggreader.app.data.entities.RssSource
import com.eggreader.app.exception.ContentEmptyException
import com.eggreader.app.model.rss.Rss
import com.eggreader.app.ui.video.VideoPlayerActivity
import com.eggreader.app.ui.widget.dialog.PhotoDialog
import com.eggreader.app.utils.NetworkUtils
import com.eggreader.app.utils.showDialogFragment
import com.eggreader.app.utils.startActivity
import kotlinx.coroutines.Dispatchers.IO

object ReadRss {
    /**
     * 通过RSS历史记录点击阅读
     */
    fun readRss(activity: AppCompatActivity, record: RssReadRecord) {
        val type = record.type
        if (type == 0) {
            activity.startActivity<ReadRssActivity> {
                putExtra("title", record.title)
                putExtra("origin", record.origin)
                putExtra("link", record.record)
                putExtra("sort", record.sort)
            }
            return
        }
        if (type == 2) {
            activity.startActivity<VideoPlayerActivity> {
                putExtra("sourceKey", record.origin)
                putExtra("sourceType", SourceType.rss)
                putExtra("record", record.record)
            }
            return
        }
        readNoHtml(activity, record, type)
    }

    fun readRss(fragment: Fragment, rssArticle: RssArticle,rssSource: RssSource? = null) {
        val rssReadRecord = rssArticle.toRecord()
        appDb.rssReadRecordDao.insertRecord(rssReadRecord)
        val type = rssArticle.type
        if (type == 0) {
            //web网页
            fragment.startActivity<ReadRssActivity> {
                putExtra("title", rssArticle.title)
                putExtra("origin", rssArticle.origin)
                putExtra("link", rssArticle.link)
                putExtra("sort", rssArticle.sort)
            }
            return
        }
        if (type == 2) {
            //视频播放
            fragment.startActivity<VideoPlayerActivity> {
                putExtra("sourceKey", rssArticle.origin)
                putExtra("sourceType", SourceType.rss)
                putExtra("record", rssArticle.link)
            }
            return
        }
        readNoHtml(fragment, rssArticle, rssSource, type)
    }

    private fun readNoHtml(fragment: Fragment, rssArticle: RssArticle, rssSource: RssSource? = null, type: Int) {
        val rssSource = rssSource ?: appDb.rssSourceDao.getByKey(rssArticle.origin)
        rssSource?.let { s ->
            val ruleContent = s.ruleContent
            if (ruleContent.isNullOrBlank()) {
                when (type) {
                    1 -> fragment.showDialogFragment(PhotoDialog(rssArticle.link))
                }
            } else {
                Rss.getContent(fragment.viewLifecycleOwner.lifecycleScope, rssArticle, ruleContent, s)
                    .onSuccess(IO) { body ->
                        if (body.isBlank()) {
                            throw ContentEmptyException("正文为空")
                        }
                        val url = NetworkUtils.getAbsoluteURL(rssArticle.link, body)
                        when (type) {
                            1 -> fragment.showDialogFragment(PhotoDialog(url))
                        }
                    }.onError {
                        AppLog.put("加载为链接的正文失败", it, true)
                    }
            }
        }
    }

    private fun readNoHtml(activity: AppCompatActivity, record: RssReadRecord, type: Int) {
        val rssSource = appDb.rssSourceDao.getByKey(record.origin)
        rssSource?.let { s ->
            val ruleContent = s.ruleContent
            if (ruleContent.isNullOrBlank()) {
                when (type) {
                    1 -> activity.showDialogFragment(PhotoDialog(record.record))
                }
            } else {
                Rss.getContent(activity.lifecycleScope, record.toRssArticle(), ruleContent, s)
                    .onSuccess(IO) { body ->
                        val url = NetworkUtils.getAbsoluteURL(record.record, body)
                        when (type) {
                            1 -> activity.showDialogFragment(PhotoDialog(url))
                        }
                    }.onError {
                        AppLog.put("加载为链接的正文失败", it, true)
                    }
            }
        }
    }

}
