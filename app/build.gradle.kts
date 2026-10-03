// 顶层构建文件。
//
// 版本选择说明：AGP 8.5.2 与 Kotlin 2.0.21 是本机 Gradle 缓存里已有的版本，
// 钉住它们可以把首次构建的下载量降到最低。换版本前先确认 Gradle 分发可用。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
