package com.merrythieves.inkit

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.inksdk.ink.InkDefaults
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var ink: InkSurfaceView
    private lateinit var store: CanvasStore

    private lateinit var txtPage: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPen: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnColor: ImageButton
    private lateinit var btnFinger: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnNew: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnBackground: ImageButton
    private lateinit var palette: LinearLayout
    private lateinit var scrollIndicator: View

    private var currentBackgroundIndex = 0

    // Rainbow + greys
    private val colors = intArrayOf(
        Color.BLACK,
        Color.parseColor("#404040"), // Dark grey
        Color.parseColor("#808080"), // Medium grey
        Color.parseColor("#C0C0C0"), // Light grey
        Color.RED,
        Color.parseColor("#FF6600"), // Orange
        Color.parseColor("#FFCC00"), // Yellow
        Color.parseColor("#00CC00"), // Green
        Color.parseColor("#00CCCC"), // Cyan
        Color.BLUE,
        Color.parseColor("#6600CC"), // Purple
        Color.parseColor("#CC00CC"), // Magenta
        Color.parseColor("#1E7B1E")  // Dark green
    )
    private var activeColor: Int = Color.BLACK

    /** Idle period after the last stroke before we encode + write the PNG.
     *  Keeps disk I/O off the path of active writing. */
    private val saveIdleDelayMs = 2_500L

    /** Single-threaded background pool for canvas saves. Min priority so the
     *  compress pass can't compete with the daemon's binder thread. */
    private val saveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "canvas-save").apply { priority = Thread.MIN_PRIORITY }
    }
    private val saveRunnable = Runnable { flushSaveAsync() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = CanvasStore(this)
        store.ensureNonEmpty()

        ink = findViewById(R.id.inkSurface)
        txtPage = findViewById(R.id.txtPage)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnPen = findViewById(R.id.btnPen)
        btnEraser = findViewById(R.id.btnEraser)
        btnColor = findViewById(R.id.btnColor)
        btnFinger = findViewById(R.id.btnFinger)
        btnClear = findViewById(R.id.btnClear)
        btnNew = findViewById(R.id.btnNew)
        btnDelete = findViewById(R.id.btnDelete)
        btnBackground = findViewById(R.id.btnBackground)
        palette = findViewById(R.id.colorPalette)
        scrollIndicator = findViewById(R.id.scrollIndicator)

        ink.touchEnabled = false
        ink.setStrokeStyle(InkDefaults.DEFAULT_STROKE_WIDTH_PX, activeColor)

        btnPrev.setOnClickListener { goToPage(store.getCurrentIndex() - 1) }
        btnNext.setOnClickListener { goToPageOrCreateNew() }
        btnPen.setOnClickListener { selectPen() }
        btnEraser.setOnClickListener { selectEraser() }
        btnColor.setOnClickListener { togglePalette() }
        btnFinger.setOnClickListener {
            ink.touchEnabled = !ink.touchEnabled
            updateToolHighlights()
        }
        btnClear.setOnClickListener { confirmClear() }
        btnNew.setOnClickListener {
            cancelSaveAndFlush()
            store.createNew(setCurrent = true, inheritBackgroundType = currentBackgroundIndex)
            loadCurrentIntoView()
        }
        btnDelete.setOnClickListener { confirmDelete() }
        btnBackground.setOnClickListener { showBackgroundPicker() }

        ink.setPageNavListener { dir ->
            val nextIdx = store.getCurrentIndex() + dir
            if (nextIdx < store.size) {
                goToPage(nextIdx)
            } else if (dir > 0) {
                // Swipe forward on last page creates new page
                btnNew.performClick()
            }
        }
        ink.setScrollListener { y, max -> updateScrollIndicator(y, max) }
        ink.setDirtyListener { schedulePersist() }

        buildPalette()
        updateToolHighlights()
        updatePageLabel()

        // Defer the first canvas load until the surface has dimensions, so
        // the document bitmap is sized correctly for vertical scroll.
        ink.post { loadCurrentIntoView() }
    }

    override fun onPause() {
        cancelSaveAndFlush()
        super.onPause()
    }

    override fun onDestroy() {
        saveExecutor.shutdown()
        super.onDestroy()
    }

    private fun goToPage(index: Int) {
        if (index < 0 || index >= store.size) return
        cancelSaveAndFlush()
        // Save current page's background setting before leaving
        store.setBackgroundType(store.getCurrentIndex(), currentBackgroundIndex)
        store.setCurrent(index)
        loadCurrentIntoView()
    }

    private fun goToPageOrCreateNew() {
        val nextIdx = store.getCurrentIndex() + 1
        if (nextIdx < store.size) {
            goToPage(nextIdx)
        } else {
            // On last page, create new page
            cancelSaveAndFlush()
            store.setBackgroundType(store.getCurrentIndex(), currentBackgroundIndex)
            store.createNew(setCurrent = true, inheritBackgroundType = currentBackgroundIndex)
            loadCurrentIntoView()
        }
    }

    private fun loadCurrentIntoView() {
        val w = ink.width
        val h = ink.height
        if (w <= 0 || h <= 0) return
        val docHeight = h * ink.documentHeightFactor
        val bmp = store.loadBitmap(store.getCurrentIndex(), w, docHeight)
        ink.setDocBitmap(bmp, scrollYReset = 0)
        // Load this page's background setting
        currentBackgroundIndex = store.getBackgroundType(store.getCurrentIndex())
        ink.setBackground(currentBackgroundIndex)
        updateToolHighlights()
        updatePageLabel()
        updateScrollIndicator(0, ink.maxScrollY())
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear canvas")
            .setMessage("Erase all ink on this canvas? This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                ink.clearCurrent()
                cancelSaveAndFlush()
            }.show()
    }

    private fun confirmDelete() {
        if (store.size == 0) return
        AlertDialog.Builder(this)
            .setTitle("Delete canvas")
            .setMessage("Delete this canvas? This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val idx = store.getCurrentIndex()
                val newIdx = store.delete(idx)
                if (newIdx < 0) {
                    store.createNew(setCurrent = true)
                }
                loadCurrentIntoView()
            }.show()
    }

    private fun selectPen() {
        ink.setStrokeStyle(InkDefaults.DEFAULT_STROKE_WIDTH_PX, activeColor)
        updateToolHighlights()
    }

    private fun selectEraser() {
        ink.setEraser(true)
        updateToolHighlights()
    }

    private fun togglePalette() {
        palette.visibility = if (palette.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun buildPalette() {
        palette.removeAllViews()
        val sizeDp = (resources.displayMetrics.density * 36).toInt()
        val marginDp = (resources.displayMetrics.density * 4).toInt()
        for (c in colors) {
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke((resources.displayMetrics.density * 1).toInt(), Color.BLACK)
                }
                layoutParams = LinearLayout.LayoutParams(sizeDp, sizeDp).apply {
                    marginStart = marginDp; marginEnd = marginDp
                }
                setOnClickListener {
                    activeColor = c
                    ink.setStrokeStyle(InkDefaults.DEFAULT_STROKE_WIDTH_PX, activeColor)
                    palette.visibility = View.GONE
                    updateToolHighlights()
                }
            }
            palette.addView(swatch)
        }
    }

    private fun updateToolHighlights() {
        val penActive = !ink.isEraser
        btnPen.background = if (penActive) getDrawable(R.drawable.bg_tool_selected) else null
        btnEraser.background = if (!penActive) getDrawable(R.drawable.bg_tool_selected) else null
        btnFinger.setImageResource(if (ink.touchEnabled) R.drawable.ic_touch_enabled else R.drawable.ic_touch_disabled)
        btnFinger.background = if (ink.touchEnabled) getDrawable(R.drawable.bg_tool_selected) else null
        // Use the color icon's tint as a hint of the active color.
        btnColor.imageTintList = android.content.res.ColorStateList.valueOf(activeColor)
        // Highlight background button when not on first option
        btnBackground.background = if (currentBackgroundIndex > 0) getDrawable(R.drawable.bg_tool_selected) else null
    }

    private fun showBackgroundPicker() {
        val options = arrayOf("No background", "Horizontal lines (ruled)", "Dot grid", "Grid")
        AlertDialog.Builder(this)
            .setTitle("Background")
            .setSingleChoiceItems(options, currentBackgroundIndex) { dialog, which ->
                currentBackgroundIndex = which
                store.setBackgroundType(store.getCurrentIndex(), which)
                ink.setBackground(which)
                updateToolHighlights()
                dialog.dismiss()
            }
            .show()
    }

    private fun updatePageLabel() {
        val total = store.size
        val current = if (total > 0) store.getCurrentIndex() + 1 else 0
        txtPage.text = "$current/$total"
        btnPrev.isEnabled = store.getCurrentIndex() > 0
        // Next button always enabled (creates new page if on last)
        btnNext.isEnabled = true
    }

    private fun updateScrollIndicator(scrollY: Int, maxScrollY: Int) {
        if (maxScrollY <= 0) {
            scrollIndicator.visibility = View.INVISIBLE
            return
        }
        scrollIndicator.visibility = View.VISIBLE
        val viewHeight = ink.height.coerceAtLeast(1)
        val frac = scrollY.toFloat() / maxScrollY.toFloat()
        val travel = (viewHeight - scrollIndicator.height).coerceAtLeast(0)
        scrollIndicator.translationY = (frac * travel)
    }

    /** Reset the idle timer. Fired by InkSurfaceView every time a stroke ends. */
    private fun schedulePersist() {
        ink.removeCallbacks(saveRunnable)
        ink.postDelayed(saveRunnable, saveIdleDelayMs)
    }

    /** Cancel a pending debounced save and immediately enqueue one (for page
     *  changes, clear, new, and lifecycle pause). */
    private fun cancelSaveAndFlush() {
        ink.removeCallbacks(saveRunnable)
        flushSaveAsync()
    }

    /** Snapshot the doc bitmap on the main thread (cheap memcpy), hand the
     *  copy to the worker thread for PNG encoding + atomic write. */
    private fun flushSaveAsync() {
        val idx = store.getCurrentIndex()
        val bmp = ink.peekDocBitmap() ?: return
        val snapshot: Bitmap = try {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot failed: ${t.message}")
            return
        }
        saveExecutor.execute {
            try {
                store.saveBitmap(idx, snapshot)
            } finally {
                snapshot.recycle()
            }
        }
    }

    private fun parseColor(hex: String): Int = Color.parseColor(hex)

    companion object { private const val TAG = "MainActivity" }
}
