package dev.ide.android

import dalvik.system.DexClassLoader
import dev.ide.core.ComposePreviewApk
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the last built app APK for compiled-bytecode Compose preview. The APK is copied into the IDE's preview
 * cache and made read-only before DexClassLoader sees it; ART rejects writable dex containers on recent Android
 * versions, and keeping the build output writable avoids breaking the next local build.
 */
object ApkComposePreviewLoader {

    private val cache = ConcurrentHashMap<String, ClassLoader>()

    fun loaderFor(apk: ComposePreviewApk, cacheRoot: Path, parent: ClassLoader?): ClassLoader {
        cache[apk.fingerprint]?.let { return it }
        return synchronized(this) {
            cache[apk.fingerprint] ?: build(apk, cacheRoot, parent).also { cache[apk.fingerprint] = it }
        }
    }

    private fun build(apk: ComposePreviewApk, cacheRoot: Path, parent: ClassLoader?): ClassLoader {
        val base = cacheRoot.resolve(apk.fingerprint)
        val dir = base.toFile().apply { mkdirs() }
        val cachedApk = File(dir, "app.apk").toPath()
        val oatDir = File(dir, "oat").apply { mkdirs() }
        Files.copy(apk.apk, cachedApk, StandardCopyOption.REPLACE_EXISTING)
        val apkFile = cachedApk.toFile()
        apkFile.setWritable(false, false)
        if (apkFile.canWrite()) {
            throw IllegalStateException("Preview APK cache is still writable: $cachedApk")
        }
        return DexClassLoader(cachedApk.toString(), oatDir.absolutePath, null, parent ?: javaClass.classLoader)
    }
}
