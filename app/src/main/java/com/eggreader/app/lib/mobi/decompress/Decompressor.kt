package com.eggreader.app.lib.mobi.decompress

interface Decompressor {

    fun decompress(data: ByteArray): ByteArray

}

