package com.merrythieves.inkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Multi-canvas store. Each canvas is a single PNG in `filesDir/canvases/<id>.png`.
 * Order and currently-selected index live in `index.json`.
 *
 * Bitmaps are loaded lazily — `loadBitmap(id)` reads from disk; `saveBitmap(id, bmp)`
 * overwrites. Index mutations (create/delete/setCurrent) flush synchronously.
 */
class CanvasStore(context: Context) {

    data class CanvasMeta(val id: String, val createdAt: Long, val backgroundType: Int = 0)

    private val dir: File = File(context.filesDir, "canvases").apply { mkdirs() }
    private val indexFile: File = File(dir, "index.json")

    private val items: MutableList<CanvasMeta> = mutableListOf()
    private var currentIndex: Int = 0

    init { load() }

    val size: Int get() = items.size
    fun getCurrentIndex(): Int = currentIndex.coerceAtMost(items.size - 1).coerceAtLeast(0)
    fun getMeta(index: Int): CanvasMeta = items[index]

    private fun load() {
        items.clear()
        if (!indexFile.exists()) {
            currentIndex = 0
            return
        }
        try {
            val obj = JSONObject(indexFile.readText())
            val arr = obj.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += CanvasMeta(
                    id = o.getString("id"),
                    createdAt = o.getLong("createdAt"),
                    backgroundType = o.optInt("backgroundType", 0),
                )
            }
            currentIndex = obj.optInt("currentIndex", items.lastIndex.coerceAtLeast(0))
        } catch (t: Throwable) {
            Log.w(TAG, "index parse failed: ${t.message}", t)
            items.clear()
            currentIndex = 0
        }
    }

    private fun flushIndex() {
        val arr = JSONArray()
        for (m in items) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("createdAt", m.createdAt)
                put("backgroundType", m.backgroundType)
            })
        }
        val obj = JSONObject().apply {
            put("items", arr)
            put("currentIndex", currentIndex)
        }
        indexFile.writeText(obj.toString())
    }

    /** Make sure at least one canvas exists. Returns the current index. */
    fun ensureNonEmpty(): Int {
        if (items.isEmpty()) {
            createNew(setCurrent = true)
        }
        return getCurrentIndex()
    }

    fun setCurrent(index: Int) {
        if (index !in items.indices) return
        currentIndex = index
        flushIndex()
    }

    /** Add a new blank canvas at the end. Returns the new index. */
    fun createNew(setCurrent: Boolean = true, inheritBackgroundType: Int = 0): Int {
        val meta = CanvasMeta(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            backgroundType = inheritBackgroundType
        )
        items.add(meta)
        val newIndex = items.lastIndex
        if (setCurrent) currentIndex = newIndex
        flushIndex()
        return newIndex
    }

    fun getBackgroundType(index: Int): Int {
        return if (index in items.indices) items[index].backgroundType else 0
    }

    fun setBackgroundType(index: Int, type: Int) {
        if (index !in items.indices) return
        items[index] = items[index].copy(backgroundType = type)
        flushIndex()
    }

    /** Delete the canvas at [index]. Returns the new current index, or -1 if no canvases left. */
    fun delete(index: Int): Int {
        if (index !in items.indices) return getCurrentIndex()
        val meta = items.removeAt(index)
        File(dir, "${meta.id}.png").delete()
        if (items.isEmpty()) {
            currentIndex = 0
            flushIndex()
            return -1
        }
        currentIndex = currentIndex.coerceAtMost(items.lastIndex)
        flushIndex()
        return currentIndex
    }

    fun loadBitmap(index: Int, width: Int, height: Int): Bitmap {
        // Always return a blank white canvas - background is drawn by InkSurfaceView
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
        }
    }

    /** Load saved strokes/content for a canvas onto a transparent bitmap (ink only, no background). */
    fun loadContent(index: Int, width: Int, height: Int): Bitmap {
        val meta = items[index]
        val file = File(dir, "${meta.id}.png")
        // Transparent base — background pattern is rendered separately by InkSurfaceView
        val blank = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (file.exists()) {
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            if (decoded != null) {
                Canvas(blank).drawBitmap(decoded, 0f, 0f, null)
                decoded.recycle()
            }
        }
        return blank
    }

    fun saveBitmap(index: Int, bitmap: Bitmap) {
        if (index !in items.indices) return
        val meta = items[index]
        val tmp = File(dir, "${meta.id}.png.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            tmp.renameTo(File(dir, "${meta.id}.png"))
        } catch (t: Throwable) {
            Log.w(TAG, "saveBitmap[$index] failed: ${t.message}")
            tmp.delete()
        }
    }

    companion object { private const val TAG = "CanvasStore" }
}
