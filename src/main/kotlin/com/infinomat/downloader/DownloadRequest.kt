package com.infinomat.downloader

import java.io.File

data class DownloadRequest(
    val url: String,
    val destination: File,
    val threadCount: Int = 4,
    val retryCount: Int = 3
)
