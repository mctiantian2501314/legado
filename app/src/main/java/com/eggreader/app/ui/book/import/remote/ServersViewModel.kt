package com.eggreader.app.ui.book.import.remote

import android.app.Application
import com.eggreader.app.base.BaseViewModel
import com.eggreader.app.data.appDb
import com.eggreader.app.data.entities.Server

class ServersViewModel(application: Application): BaseViewModel(application) {


    fun delete(server: Server) {
        execute {
            appDb.serverDao.delete(server)
        }
    }

}
