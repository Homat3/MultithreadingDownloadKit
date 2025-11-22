package com.infinomat.downloader

import java.io.File

interface DownloadListener {
    fun onStart()
    fun onProgress(downloaded: Long, total: Long, percent: Int)
    fun onComplete(file: File)
    fun onError(error: Throwable)
}
