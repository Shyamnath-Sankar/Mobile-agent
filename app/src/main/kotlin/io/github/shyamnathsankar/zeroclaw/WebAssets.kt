package io.github.shyamnathsankar.zeroclaw

import android.content.Context
import java.io.File

/** Copies the bundled ZeroClaw dashboard (assets/web-dist) to app storage so the gateway can serve it. */
object WebAssets {
    private const val ASSET_DIR = "web-dist"

    fun distDir(context: Context) = File(context.filesDir, "web/dist")

    @Synchronized
    fun ensureExtracted(context: Context) {
        val dist = distDir(context)
        val marker = File(dist.parentFile, ".extracted")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == stamp && File(dist, "index.html").exists()) return

        dist.deleteRecursively()
        dist.mkdirs()
        copyAssetDir(context, ASSET_DIR, dist)
        marker.writeText(stamp)
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetDir(context, "$assetPath/$child", File(target, child))
    }
}
