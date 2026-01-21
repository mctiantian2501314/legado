package com.eggreader.app.model

import android.content.Context
import com.eggreader.app.constant.IntentAction
import com.eggreader.app.service.DownloadService
import com.eggreader.app.utils.startService

object Download {


    fun start(context: Context, url: String, fileName: String) {
        context.startService<DownloadService> {
            action = IntentAction.start
            putExtra("url", url)
            putExtra("fileName", fileName)
        }
    }

}
