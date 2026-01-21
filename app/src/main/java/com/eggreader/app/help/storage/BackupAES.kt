package com.eggreader.app.help.storage

import cn.hutool.crypto.symmetric.AES
import com.eggreader.app.help.config.LocalConfig
import com.eggreader.app.utils.MD5Utils

class BackupAES : AES(
    MD5Utils.md5Encode(LocalConfig.password ?: "").encodeToByteArray(0, 16)
)
