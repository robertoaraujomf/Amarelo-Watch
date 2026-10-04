package br.com.amarelowatch

import android.content.Context
import android.content.SharedPreferences

data class StreamConfig(
    val shortEdge: Int,
    val fps: Int,
    val quality: Int,
    val port: Int,
) {
    val fpsIntervalNs: Long get() = 1_000_000_000L / fps
}

object Settings {

    private const val FILE = "amarelo"
    private const val KEY_SHORT_EDGE = "short_edge"
    private const val KEY_FPS = "fps"
    private const val KEY_QUALITY = "quality"
    private const val KEY_PORT = "port"

    val DEFAULT_PORT = 8080

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): StreamConfig {
        val p = prefs(context)
        return StreamConfig(
            shortEdge = p.getInt(KEY_SHORT_EDGE, 720),
            fps = p.getInt(KEY_FPS, 15).coerceIn(5, 30),
            quality = p.getInt(KEY_QUALITY, 70).coerceIn(30, 95),
            port = p.getInt(KEY_PORT, DEFAULT_PORT),
        )
    }

    fun save(context: Context, config: StreamConfig) {
        prefs(context).edit()
            .putInt(KEY_SHORT_EDGE, config.shortEdge)
            .putInt(KEY_FPS, config.fps)
            .putInt(KEY_QUALITY, config.quality)
            .putInt(KEY_PORT, config.port)
            .apply()
    }
}
