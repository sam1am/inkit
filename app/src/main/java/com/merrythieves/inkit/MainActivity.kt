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
import android.widget.PopupMenu
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
    private lateinit var btnNew: ImageButton
    private lateinit var btnUndo: ImageButton
    private lateinit var btnMore: ImageButton
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

    /** True once the current canvas's saved content has been loaded into the
     *  view. Save attempts before this flag is set would persist a transient
     *  blank docBitmap (e.g. one created by a surface recreate) over the
     *  good on-disk state. */
    private var contentLoaded: Boolean = false

    /** Nesting count of UI surfaces (popup menus, dialogs, palette) currently
     *  occluding the canvas. The daemon dispatches pen events based on view
     *  bounds, not Z-order, so without disabling its input these surfaces
     *  would let pen taps draw on the canvas underneath. */
    private var penDisableCount: Int = 0

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
        btnNew = findViewById(R.id.btnNew)
        btnUndo = findViewById(R.id.btnUndo)
        btnMore = findViewById(R.id.btnMore)
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
        btnMore.setOnClickListener { showMoreMenu() }
        btnNew.setOnClickListener {
            cancelSaveAndFlush()
            store.createNew(setCurrent = true, inheritBackgroundType = currentBackgroundIndex)
            loadCurrentIntoView()
        }
        btnUndo.setOnClickListener {
            if (ink.undo()) updateUndoButton()
        }
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
        ink.setUndoListener { updateUndoButton() }
        // If the surface is destroyed and recreated (e.g. brief backgrounding,
        // config change), reload from disk in case the in-memory bitmap was
        // lost. With InkSurfaceView's bitmaps now retained across the surface
        // lifecycle this is mostly a safety net for process-death restoration.
        ink.setSurfaceReadyListener {
            if (!contentLoaded) loadCurrentIntoView()
        }

        buildPalette()
        updateToolHighlights()
        updatePageLabel()
        updateUndoButton()

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
        contentLoaded = false
        val docHeight = h * ink.documentHeightFactor
        // Load this page's background setting first
        currentBackgroundIndex = store.getBackgroundType(store.getCurrentIndex())
        // Load saved content
        val content = store.loadContent(store.getCurrentIndex(), w, docHeight)
        ink.setDocBitmapWithContent(content, currentBackgroundIndex, scrollYReset = 0)
        updateToolHighlights()
        updatePageLabel()
        updateScrollIndicator(0, ink.maxScrollY())
        updateUndoButton()
        contentLoaded = true
    }

    private fun updateUndoButton() {
        val enabled = ink.canUndo()
        btnUndo.isEnabled = enabled
        btnUndo.alpha = if (enabled) 1.0f else 0.35f
    }

    private fun showMoreMenu() {
        val popup = PopupMenu(this, btnMore)
        popup.menu.add(0, 1, 0, "Clear canvas")
        popup.menu.add(0, 2, 1, "Delete canvas")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmClear()
                2 -> confirmDelete()
            }
            true
        }
        popup.setOnDismissListener { popPenDisable() }
        pushPenDisable()
        popup.show()
    }

    private fun confirmClear() {
        pushPenDisable()
        AlertDialog.Builder(this)
            .setTitle("Clear canvas")
            .setMessage("Erase all ink on this canvas? This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                ink.clearCurrent()
                cancelSaveAndFlush()
            }
            .setOnDismissListener { popPenDisable() }
            .show()
    }

    private fun confirmDelete() {
        if (store.size == 0) return
        pushPenDisable()
        AlertDialog.Builder(this)
            .setTitle("Delete canvas")
            .setMessage("Delete this canvas? This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                // Drop any pending debounced save without flushing — we're
                // about to delete this canvas, so saving its current state
                // would just orphan a file.
                ink.removeCallbacks(saveRunnable)
                val idx = store.getCurrentIndex()
                val newIdx = store.delete(idx)
                if (newIdx < 0) {
                    store.createNew(setCurrent = true, inheritBackgroundType = currentBackgroundIndex)
                }
                loadCurrentIntoView()
            }
            .setOnDismissListener { popPenDisable() }
            .show()
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
        setPaletteVisible(palette.visibility != View.VISIBLE)
    }

    private fun setPaletteVisible(visible: Boolean) {
        val wasVisible = palette.visibility == View.VISIBLE
        if (visible == wasVisible) return
        palette.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) pushPenDisable() else popPenDisable()
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
                    setPaletteVisible(false)
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
        btnBackground.background = null
    }

    private fun showBackgroundPicker() {
        val options = arrayOf("No background", "Horizontal lines (ruled)", "Dot grid", "Grid")
        pushPenDisable()
        AlertDialog.Builder(this)
            .setTitle("Background")
            .setSingleChoiceItems(options, currentBackgroundIndex) { dialog, which ->
                currentBackgroundIndex = which
                store.setBackgroundType(store.getCurrentIndex(), which)
                ink.setBackground(which)
                updateToolHighlights()
                dialog.dismiss()
            }
            .setOnDismissListener { popPenDisable() }
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

    private fun pushPenDisable() {
        if (penDisableCount == 0) ink.setOverlayEnabled(false)
        penDisableCount++
    }

    private fun popPenDisable() {
        penDisableCount = (penDisableCount - 1).coerceAtLeast(0)
        if (penDisableCount == 0) ink.setOverlayEnabled(true)
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
        if (!contentLoaded) {
            Log.i(TAG, "skip save — current canvas not yet loaded from disk")
            return
        }
        val idx = store.getCurrentIndex()
        // Capture the canvas id on the main thread so the bg write can't be
        // redirected onto a different canvas if the index is mutated (delete,
        // createNew, page switch) before the worker thread picks the task up.
        val id = store.getMeta(idx).id
        val bmp = ink.peekDocBitmap() ?: return
        Log.i(TAG, "flushSaveAsync: id=$id docId=${System.identityHashCode(bmp)}")
        val snapshot: Bitmap = try {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot failed: ${t.message}")
            return
        }
        saveExecutor.execute {
            try {
                store.saveBitmap(id, snapshot)
                Log.i(TAG, "flushSaveAsync: wrote $id to disk")
            } finally {
                snapshot.recycle()
            }
        }
    }

    private fun parseColor(hex: String): Int = Color.parseColor(hex)

    companion object { private const val TAG = "MainActivity" }
}
