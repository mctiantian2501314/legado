package com.eggreader.app.ui.login

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eggreader.app.R
import com.eggreader.app.constant.EventBus
import com.eggreader.app.data.entities.BaseSource
import com.eggreader.app.data.entities.HttpTTS
import com.eggreader.app.model.ReadAloud
import com.eggreader.app.ui.rss.read.RssJsExtensions
import com.eggreader.app.utils.FileUtils
import com.eggreader.app.utils.postEvent
import com.eggreader.app.utils.sendToClip
import com.eggreader.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import java.io.File

class SourceLoginJsExtensions(
    activity: AppCompatActivity?, source: BaseSource?,
    private val callback: Callback? = null
) : RssJsExtensions(activity, source) {

    interface Callback {
        fun upUiData(data: Map<String, String?>?)
        fun reUiView()
    }

    fun upLoginData(data: Map<String, String?>?) {
        callback?.upUiData(data)
    }

    fun reLoginView() {
        callback?.reUiView()
    }

    fun refreshExplore() {
        callback?.reUiView()
    }

    fun refreshBookInfo() {
        postEvent(EventBus.REFRESH_BOOK_INFO, true)
    }

    fun copyText(text: String) {
        activityRef.get()?.sendToClip(text)
    }

    fun clearTtsCache() {
        if (getSource() !is HttpTTS) return
        val activity = activityRef.get() ?: return
        activity.lifecycleScope.launch(IO) {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath = "${activity.cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            activity.toastOnUi(R.string.clear_cache_success)
        }
    }
}
