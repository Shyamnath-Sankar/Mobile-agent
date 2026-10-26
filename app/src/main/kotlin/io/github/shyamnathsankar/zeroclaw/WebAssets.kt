package io.github.shyamnathsankar.zeroclaw

import android.content.Context
import java.io.File

/**
 * Copies the bundled ZeroClaw dashboard (assets/web-dist) to app storage so the gateway can serve it,
 * then layers the app theme (assets/zc-theme) on top. The gateway serves the dist dir under `/_app/`.
 */
object WebAssets {
    private const val DIST_ASSET_DIR = "web-dist"
    private const val THEME_ASSET_DIR = "zc-theme"
    private const val THEME_LINK = """<link rel="stylesheet" href="/_app/zc-theme/theme.css">"""

    fun distDir(context: Context) = File(context.filesDir, "web/dist")

    @Synchronized
    fun ensureExtracted(context: Context) {
        val dist = distDir(context)
        val marker = File(dist.parentFile, ".extracted")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == stamp && File(dist, "index.html").exists()) return

        dist.deleteRecursively()
        dist.mkdirs()
        copyAssetDir(context, DIST_ASSET_DIR, dist)
        copyAssetDir(context, THEME_ASSET_DIR, File(dist, THEME_ASSET_DIR))
        injectTheme(File(dist, "index.html"))
        marker.writeText(stamp)
    }

    private fun injectTheme(index: File) {
        val html = index.readText()
        if (html.contains(THEME_LINK)) return
        // Last in <head> so it wins over the dashboard's own stylesheet.
        index.writeText(html.replaceFirst("</head>", "$THEME_LINK</head>"))
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
