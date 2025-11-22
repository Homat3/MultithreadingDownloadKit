package com.infinomat.downloader

import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RealDownloadTest {

    @Test
    fun `test real download with pause and resume`() {
        val url = "https://raw.githubusercontent.com/octocat/Spoon-Knife/master/index.html" // Small test file
        val dest = File("downloaded_index.html")
        if (dest.exists()) dest.delete()
        
        val request = DownloadRequest(url, dest)
        val latch = CountDownLatch(1)
        val downloader = Downloader()

        println("Starting download from $url")

        thread {
            downloader.download(request, object : DownloadListener {
                override fun onStart() {
                    println("Download started...")
                }

                override fun onProgress(downloaded: Long, total: Long, percent: Int) {
                    println("Progress: $percent% ($downloaded / $total)")
                    try {
                        Thread.sleep(50) // Slow down to allow pause
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }

                override fun onComplete(file: File) {
                    println("Download complete: ${file.absolutePath}")
                    latch.countDown()
                }

                override fun onError(error: Throwable) {
                    println("Error: ${error.message}")
                    error.printStackTrace()
                    latch.countDown()
                }
            })
        }

        Thread.sleep(200)
        println("Pausing download...")
        downloader.pause()
        println("Status after pause: ${downloader.status}")
        
        Thread.sleep(1000)
        if (downloader.status == DownloadStatus.PAUSED) {
            println("Download successfully paused.")
            println("Resuming download...")
            downloader.resume()
        } else {
            println("Download failed to pause or finished too quickly.")
        }
        
        latch.await(30, TimeUnit.SECONDS)
        println("Test finished.")
        
        if (dest.exists()) {
            println("File downloaded successfully: ${dest.length()} bytes")
            dest.delete()
        } else {
            throw Exception("File not downloaded")
        }
    }
}
