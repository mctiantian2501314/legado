package com.eggreader.app.utils.canvasrecorder.pools

import android.graphics.Picture
import com.eggreader.app.utils.objectpool.BaseObjectPool

class PicturePool : BaseObjectPool<Picture>(64) {

    override fun create(): Picture = Picture()

}

