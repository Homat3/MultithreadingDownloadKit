package com.infinomat.downloader

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载器
 * @param threadCount 下载线程数
 */
class Downloader(val threadCount: Int = 4) {


    /**
     * Http 客户端内核
     */
    private val client = OkHttpClient()

    /**
     * 当前下载状态
     */
    @Volatile
    var status: DownloadStatus = DownloadStatus.IDLE
        private set

    /**
     * 当前下载参数
     */
    private var currentRequest: DownloadRequest? = null
    private var currentListener: DownloadListener? = null
    private val chunkProgress = ConcurrentHashMap<Int, Long>()

    /**
     * 暂停下载
     */
    fun pause() {
        if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PREPARING) {
            status = DownloadStatus.PAUSED
        }
    }

    /**
     * 继续下载
     */
    fun resume() {
        if (status == DownloadStatus.PAUSED) {
            val req = currentRequest
            val lis = currentListener
            if (req != null && lis != null) {
                download(req, lis)
            }
        }
    }

    /**
     * 下载启动函数
     * @param request 下载请求
     * @param listener 监听器
     */
    fun download(request: DownloadRequest, listener: DownloadListener) {
        currentRequest = request
        currentListener = listener
        runBlocking {
            downloadAsync(request, listener)
        }
    }

    /**
     * 启动下载任务
     * @param request 下载请求
     * @param listener 监听器
     */
    suspend fun downloadAsync(request: DownloadRequest, listener: DownloadListener) = coroutineScope {
        try {
            val isResuming = status == DownloadStatus.PAUSED
            status = DownloadStatus.PREPARING
            listener.onStart()

            // 1. Get content length and check range support
            val headRequest = Request.Builder().url(request.url).head().build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(headRequest).execute()
            }

            if (!response.isSuccessful) {
                status = DownloadStatus.FAILED
                throw Exception("Failed to connect: ${response.code}")
            }

            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val acceptRanges = response.header("Accept-Ranges") == "bytes"
            response.close()

            if (status == DownloadStatus.PAUSED) return@coroutineScope

            if (contentLength <= 0) {
                // Fallback to single thread if length unknown
                status = DownloadStatus.DOWNLOADING
                downloadSingleThread(request, listener, 0)
                if (status != DownloadStatus.PAUSED) {
                    status = DownloadStatus.COMPLETED
                    listener.onComplete(request.destination)
                }
                return@coroutineScope
            }

            // Prepare file
            val file = request.destination
            if (!isResuming) {
                if (file.exists()) {
                    file.delete()
                }
                file.parentFile?.mkdirs()
                file.createNewFile()
                
                // Set file size
                withContext(Dispatchers.IO) {
                    RandomAccessFile(file, "rw").use { it.setLength(contentLength) }
                }
                chunkProgress.clear()
            }

            if (status == DownloadStatus.PAUSED) return@coroutineScope

            status = DownloadStatus.DOWNLOADING
            if (!acceptRanges || threadCount <= 1) {
                val start = if (isResuming) file.length() else 0L
                // For single thread resume, we assume file length is what we downloaded
                // But RandomAccessFile setLength might have made it full size?
                // If we setLength, file.length() is full size.
                // So for single thread, we can't rely on file.length() if we pre-allocated.
                // But wait, if we pre-allocated, we can't easily resume single thread without tracking progress.
                // Let's assume for single thread we DON'T pre-allocate if we want to support simple resume?
                // Or we track progress.
                // Since we use chunkProgress for multi-thread, let's use it for single thread too (index 0).
                val downloaded = chunkProgress[0] ?: 0L
                downloadSingleThread(request, listener, downloaded)
            } else {
                downloadMultiThread(request, contentLength, listener)
            }

            if (status == DownloadStatus.PAUSED) {
                return@coroutineScope
            }

            status = DownloadStatus.COMPLETED
            listener.onComplete(file)

        } catch (e: Exception) {
            if (status != DownloadStatus.PAUSED) {
                status = DownloadStatus.FAILED
                listener.onError(e)
            }
        }
    }

    /**
     * 单线程下载
     * @param request 下载请求
     * @param listener 监听器
     * @param startOffset 起始偏移量
     */
    private suspend fun downloadSingleThread(request: DownloadRequest, listener: DownloadListener, startOffset: Long) {
        withContext(Dispatchers.IO) {
            val reqBuilder = Request.Builder().url(request.url)
            if (startOffset > 0) {
                reqBuilder.header("Range", "bytes=$startOffset-")
            }
            val req = reqBuilder.build()
            
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Failed to download: ${response.code}")
                
                val body = response.body ?: throw Exception("No body")
                val total = body.contentLength() + startOffset // Total is remaining + start
                var downloaded = startOffset
                
                val buffer = ByteArray(8192)
                val inputStream = body.byteStream()
                val outputStream = RandomAccessFile(request.destination, "rw")
                outputStream.seek(startOffset)
                
                outputStream.use { out ->
                    var bytesRead = inputStream.read(buffer)
                    while (bytesRead >= 0) {
                        if (status == DownloadStatus.PAUSED) {
                            chunkProgress[0] = downloaded
                            return@use
                        }
                        out.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        chunkProgress[0] = downloaded
                        
                        // Note: total might be unknown (-1) if server doesn't send content-length for range
                        // But we passed contentLength from outside usually.
                        // Here we use body.contentLength() which is partial.
                        // Let's just report progress.
                        // If total is -1, we can't report percent accurately.
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            listener.onProgress(downloaded, total, percent)
                        }
                        bytesRead = inputStream.read(buffer)
                    }
                }
            }
        }
    }

    /**
     * 多线程下载
     * @param request 下载请求
     * @param totalLength 文件总长度
     * @param listener 监听器
     */
    private suspend fun downloadMultiThread(
        request: DownloadRequest,
        totalLength: Long,
        listener: DownloadListener
    ) = coroutineScope {
        val chunkSize = totalLength / threadCount
        val downloadedTotal = AtomicLong(0)
        
        // Calculate initial downloaded total from chunkProgress
        chunkProgress.values.forEach { downloadedTotal.addAndGet(it) }

        val jobs = (0 until threadCount).map { index ->
            async(Dispatchers.IO) {
                val start = index * chunkSize
                val end = if (index == threadCount - 1) totalLength - 1 else (start + chunkSize - 1)
                
                val alreadyDownloaded = chunkProgress[index] ?: 0L
                val currentStart = start + alreadyDownloaded
                
                if (currentStart <= end) {
                    downloadChunk(request.url, request.destination, currentStart, end, downloadedTotal, totalLength, listener, index)
                }
            }
        }
        jobs.awaitAll()
    }

    /**
     * 下载一个分块
     * @param url 下载地址
     * @param file 保存文件
     * @param start 起始位置
     * @param end 结束位置
     * @param downloadedTotal 已下载总大小
     * @param totalLength 文件总大小
     * @param listener 监听器
     * @param chunkIndex 分块索引
     */
    private fun downloadChunk(
        url: String,
        file: File,
        start: Long,
        end: Long,
        downloadedTotal: AtomicLong,
        totalLength: Long,
        listener: DownloadListener,
        chunkIndex: Int
    ) {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Chunk download failed: ${response.code}")

            val body = response.body ?: throw Exception("No chunk body")
            val inputStream = body.byteStream()
            
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(8192)
                var bytesRead = inputStream.read(buffer)
                while (bytesRead >= 0) {
                    if (status == DownloadStatus.PAUSED) {
                        return@use
                    }
                    raf.write(buffer, 0, bytesRead)
                    
                    // Update chunk progress
                    val currentChunkDownloaded = (raf.filePointer - (start - (chunkProgress[chunkIndex] ?: 0))) // This logic is tricky
                    // Easier: track bytes read in this session and add to existing
                    // But we need absolute progress for resume.
                    // chunkProgress stores "how many bytes downloaded for this chunk from its start"
                    // So:
                    val bytesInThisSession = bytesRead.toLong() // Wait, we need to accumulate
                    // Let's just update chunkProgress by adding bytesRead
                    chunkProgress.compute(chunkIndex) { _, v -> (v ?: 0) + bytesRead }
                    
                    val currentTotal = downloadedTotal.addAndGet(bytesRead.toLong())
                    val percent = ((currentTotal * 100) / totalLength).toInt()
                    listener.onProgress(currentTotal, totalLength, percent)
                    bytesRead = inputStream.read(buffer)
                }
            }
        }
    }
}
