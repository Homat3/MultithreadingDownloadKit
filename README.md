# 多线程下载工具包

**版本**: 1.0.0
**作者**: Infinomat
**版权所有**: © 2025 Infinomat。保留所有权利。

一个强大的多线程文件下载库，适用于 Kotlin/Java 应用程序。它支持并发下载、暂停/恢复功能和详细进度跟踪。

## 功能特性

- **多线程**: 通过将文件分割成块来加速下载。
- **暂停与恢复**: 暂停下载并从上次中断的地方继续下载。
- **进度跟踪**: 通过回调监听器实现实时进度更新。
- **弹性**: 自动重试和错误处理（可配置）。
- **Kotlin 协程**: 基于 Kotlin 协程构建，实现高效的异步处理。

## 安装

将依赖项添加到您的 `build.gradle.kts` 文件中：

```kotlin
dependencies {
    implementation(files("/lib/MultithreadingDownloadKit-x.x.x.jar"))
}
```

## 使用方法

### 基本下载

```kotlin
import com.infinomat.downloader.*
import java.io.File

fun main() {
    val url = "https://example.com/largefile.zip"
    val destination = File("downloads/largefile.zip")
    
    // 创建请求
    val request = DownloadRequest(url, destination)
    
    // 创建下载器实例（默认4个线程）
    val downloader = Downloader()
    
    // 开始下载
    downloader.download(request, object : DownloadListener {
        override fun onStart() {
            println("下载开始")
        }

        override fun onProgress(downloaded: Long, total: Long, percent: Int) {
            println("进度: $percent% ($downloaded / $total)")
        }

        override fun onComplete(file: File) {
            println("下载完成: ${file.absolutePath}")
        }

        override fun onError(error: Throwable) {
            println("错误: ${error.message}")
        }
    })
}
```

### 自定义线程数

您可以在创建 [Downloader](file:///E:/Documents/Kotlin/MinecraftLauncher/Modules/MultithreadingDownloadKit/src/main/kotlin/com/infinomat/downloader/Downloader.kt#L15-L105) 实例时指定线程数量：

```kotlin
// 使用8个线程进行下载
val downloader = Downloader(threadCount = 8)
```

### 暂停和恢复

您可以使用 [pause()](file:///E:/Documents/Kotlin/MinecraftLauncher/Modules/MultithreadingDownloadKit/src/main/kotlin/com/infinomat/downloader/Downloader.kt#L81-L83) 和 [resume()](file:///E:/Documents/Kotlin/MinecraftLauncher/Modules/MultithreadingDownloadKit/src/main/kotlin/com/infinomat/downloader/Downloader.kt#L85-L93) 方法来暂停和恢复下载。

```kotlin
val downloader = Downloader()

// 开始下载...
downloader.download(request, listener)

// 暂停
downloader.pause()

// 检查状态
if (downloader.status == DownloadStatus.PAUSED) {
    println("已暂停")
}

// 恢复
downloader.resume()
```

## API 参考

### `Downloader`

- `constructor(threadCount: Int = 4)`: 创建具有指定线程数的新下载器。
- `download(request: DownloadRequest, listener: DownloadListener)`: 启动下载（阻塞包装器）。
- `suspend downloadAsync(request: DownloadRequest, listener: DownloadListener)`: 异步启动下载。
- `pause()`: 暂停当前下载。
- `resume()`: 恢复已暂停的下载。
- `status`: 当前 [DownloadStatus](file:///E:/Documents/Kotlin/MinecraftLauncher/Modules/MultithreadingDownloadKit/src/main/kotlin/com/infinomat/downloader/DownloadStatus.kt#L1-L12)（IDLE, PREPARING, DOWNLOADING, PAUSED, COMPLETED, FAILED）。

### `DownloadRequest`

- `url`: 要下载的 URL。
- `destination`: 要保存到的本地文件。
- `threadCount`: （已弃用，请使用 Downloader 构造函数）线程数。
- `retryCount`: 失败时的重试次数。

### `DownloadListener`

- `onStart()`: 下载开始时调用。
- `onProgress(downloaded: Long, total: Long, percent: Int)`: 定期使用进度调用。
- `onComplete(file: File)`: 下载成功完成时调用。
- `onError(error: Throwable)`: 发生错误时调用。