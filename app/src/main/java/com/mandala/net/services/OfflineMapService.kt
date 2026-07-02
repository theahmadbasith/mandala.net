package com.mandala.net.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

data class RegionBoundingBox(
    val name: String,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double
)

object OfflineMapService {
    
    val supportedRegions = listOf(
        RegionBoundingBox("Jawa Timur", -8.788, -6.643, 110.898, 114.629),
        RegionBoundingBox("Jawa Tengah", -8.211, -6.368, 108.555, 111.692),
        RegionBoundingBox("Jawa Barat", -7.778, -5.922, 106.370, 108.831),
        RegionBoundingBox("DKI Jakarta", -6.375, -6.082, 106.685, 106.974),
        RegionBoundingBox("Ponorogo", -8.077, -7.724, 111.285, 111.758)
    )
    
    private const val TAG = "OfflineMapService"
    
    fun getTileDirectory(context: Context): File {
        val dir = File(context.filesDir, "map_tiles")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    fun isTileCached(context: Context, z: Int, x: Int, y: Int): Boolean {
        val file = File(getTileDirectory(context), "${z}_${x}_${y}.png")
        return file.exists() && file.length() > 0
    }

    fun getCacheSize(context: Context): Long {
        return getTileDirectory(context).walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    fun clearCache(context: Context) {
        getTileDirectory(context).listFiles()?.forEach { it.delete() }
    }

    suspend fun downloadRegion(
        context: Context,
        regionName: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val region = supportedRegions.find { it.name == regionName } ?: return@withContext false
        
        // Define zoom levels to download (10 to 14 for a good balance of detail and size)
        val zoomLevels = 10..13
        
        val tilesToDownload = mutableListOf<Triple<Int, Int, Int>>()
        
        for (z in zoomLevels) {
            val minX = lon2tilex(region.minLng, z)
            val maxX = lon2tilex(region.maxLng, z)
            val minY = lat2tiley(region.maxLat, z) // maxLat -> minY since y is inverted
            val maxY = lat2tiley(region.minLat, z)
            
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    if (!isTileCached(context, z, x, y)) {
                        tilesToDownload.add(Triple(z, x, y))
                    }
                }
            }
        }
        
        // Cap maximum tiles to avoid huge downloads in one go
        val maxTiles = 2000
        val targetTiles = if (tilesToDownload.size > maxTiles) tilesToDownload.shuffled().take(maxTiles) else tilesToDownload
        val totalTiles = targetTiles.size
        
        if (totalTiles == 0) {
            withContext(Dispatchers.Main) { onProgress(1f) }
            return@withContext true
        }
        
        var downloaded = 0
        
        for ((z, x, y) in targetTiles) {
            val success = downloadTile(context, z, x, y)
            if (success) {
                downloaded++
                withContext(Dispatchers.Main) {
                    onProgress(downloaded.toFloat() / totalTiles.toFloat())
                }
            }
        }
        
        return@withContext true
    }
    
    private fun downloadTile(context: Context, z: Int, x: Int, y: Int): Boolean {
        // We'll use Google Road Map for tiles
        val urlString = "https://mt1.google.com/vt/lyrs=m&x=$x&y=$y&z=$z"
        val tileFile = File(getTileDirectory(context), "${z}_${x}_${y}.png")
        
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(tileFile)
                
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                
                outputStream.close()
                inputStream.close()
                return true
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download tile z=$z x=$x y=$y: ${e.message}")
        }
        
        return false
    }

    private fun lon2tilex(lon: Double, z: Int): Int {
        return Math.floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()
    }

    private fun lat2tiley(lat: Double, z: Int): Int {
        val latRad = Math.toRadians(lat)
        return Math.floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl z)).toInt()
    }
}
