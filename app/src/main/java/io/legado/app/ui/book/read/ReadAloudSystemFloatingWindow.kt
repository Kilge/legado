package io.legado.app.ui.book.read

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.utils.LogUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import kotlin.math.roundToInt

internal class ReadAloudSystemFloatingWindow(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
    private val onPlayPause: () -> Unit,
    private val onExpand: () -> Unit,
    private val onClose: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var uiState by mutableStateOf(ReadAloudPlayerPanel.PlayerUiState())
    private var themeRevision by mutableIntStateOf(0)
    private var suppressed = false
    private var attached = false
    private var dragging = false
    private var bounds = ReadAloudFloatingWindowBounds(0, 0, 0, 0)

    private val windowWidth = (READ_ALOUD_CAPSULE_WIDTH_DP + SHADOW_PADDING_DP * 2).dpToPx()
    private val windowHeight = (READ_ALOUD_CAPSULE_HEIGHT_DP + SHADOW_PADDING_DP * 2).dpToPx()

    private val layoutParams = WindowManager.LayoutParams(
        windowWidth,
        windowHeight,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.START or Gravity.TOP
        title = "Legado read aloud controls"
    }

    private val composeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            val revision = themeRevision
            val palette = remember(revision) { ReaderSheetStyle.resolve(context) }
            val colors = rememberPlayerColors(palette)
            val coverRotation = remember { Animatable(0f) }
            val rotating = uiState.playing && !AppConfig.isEInkMode
            LaunchedEffect(rotating) {
                if (rotating) {
                    while (true) {
                        val start = coverRotation.value % 360f
                        coverRotation.snapTo(start)
                        coverRotation.animateTo(
                            targetValue = start + 360f,
                            animationSpec = tween(durationMillis = 16000, easing = LinearEasing)
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(SHADOW_PADDING_DP.dp)
            ) {
                ReadAloudCapsuleSurface(
                    state = uiState,
                    colors = colors,
                    onPlayPause = onPlayPause,
                    onExpand = onExpand,
                    onClose = onClose,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { startDrag() },
                                onDragEnd = { finishDrag() },
                                onDragCancel = { finishDrag() }
                            ) { change, amount ->
                                change.consume()
                                dragBy(amount.x.roundToInt(), amount.y.roundToInt())
                            }
                        },
                    coverRotation = coverRotation.value
                )
            }
        }
    }

    fun showOrUpdate(state: ReadAloudPlayerPanel.PlayerUiState) {
        uiState = state
        if (suppressed || !state.serviceRunning) {
            remove()
        } else {
            attachIfNeeded()
        }
    }

    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        if (value) remove() else if (uiState.serviceRunning) attachIfNeeded()
    }

    fun refreshTheme() {
        themeRevision += 1
    }

    fun onConfigurationChanged() {
        if (!attached) return
        placeFromStoredPosition()
        updateLayout()
        refreshTheme()
    }

    fun remove() {
        if (!attached) return
        runCatching { windowManager.removeViewImmediate(composeView) }
            .onFailure { LogUtils.d(TAG, "remove floating window failed: ${it.localizedMessage}") }
        attached = false
        dragging = false
    }

    fun dispose() {
        remove()
        composeView.disposeComposition()
    }

    private fun attachIfNeeded() {
        if (attached || !canDrawOverlays()) return
        placeFromStoredPosition()
        runCatching {
            windowManager.addView(composeView, layoutParams)
            attached = true
        }.onFailure {
            attached = false
            LogUtils.d(TAG, "add floating window failed: ${it.localizedMessage}")
        }
    }

    private fun placeFromStoredPosition() {
        bounds = resolveBounds()
        val side = context.getPrefInt(PreferKey.readAloudFloatingBallSide, 1).coerceIn(0, 1)
        val yPercent = context.getPrefInt(PreferKey.readAloudFloatingBallYPercent, 72)
            .coerceIn(0, 100)
        layoutParams.x = ReadAloudFloatingWindowLayout.xForSide(side, bounds)
        layoutParams.y = ReadAloudFloatingWindowLayout.yForPercent(yPercent, bounds)
    }

    private fun startDrag() {
        if (!attached) return
        dragging = true
        bounds = resolveBounds()
    }

    private fun dragBy(dx: Int, dy: Int) {
        if (!attached || !dragging) return
        layoutParams.x = (layoutParams.x + dx).coerceIn(bounds.minX, bounds.maxX)
        layoutParams.y = (layoutParams.y + dy).coerceIn(bounds.minY, bounds.maxY)
        updateLayout()
    }

    private fun finishDrag() {
        if (!attached || !dragging) return
        dragging = false
        val side = ReadAloudFloatingWindowLayout.sideForX(layoutParams.x, bounds)
        layoutParams.x = ReadAloudFloatingWindowLayout.xForSide(side, bounds)
        layoutParams.y = layoutParams.y.coerceIn(bounds.minY, bounds.maxY)
        context.putPrefInt(PreferKey.readAloudFloatingBallSide, side)
        context.putPrefInt(
            PreferKey.readAloudFloatingBallYPercent,
            ReadAloudFloatingWindowLayout.percentForY(layoutParams.y, bounds)
        )
        updateLayout()
    }

    private fun updateLayout() {
        if (!attached) return
        runCatching { windowManager.updateViewLayout(composeView, layoutParams) }
            .onFailure {
                LogUtils.d(TAG, "update floating window failed: ${it.localizedMessage}")
                remove()
            }
    }

    private fun resolveBounds(): ReadAloudFloatingWindowBounds {
        var width = context.resources.displayMetrics.widthPixels
        var height = context.resources.displayMetrics.heightPixels
        var insetLeft = 0
        var insetTop = statusBarHeight()
        var insetRight = 0
        var insetBottom = navigationBarHeight()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            width = metrics.bounds.width()
            height = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            insetLeft = insets.left
            insetTop = insets.top
            insetRight = insets.right
            insetBottom = insets.bottom
        }
        return ReadAloudFloatingWindowLayout.bounds(
            screenWidth = width,
            screenHeight = height,
            insetLeft = insetLeft,
            insetTop = insetTop,
            insetRight = insetRight,
            insetBottom = insetBottom,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            sideMargin = SIDE_MARGIN_DP.dpToPx(),
            bottomMargin = BOTTOM_MARGIN_DP.dpToPx()
        )
    }

    private fun statusBarHeight(): Int = systemDimension("status_bar_height")

    private fun navigationBarHeight(): Int = systemDimension("navigation_bar_height")

    private fun systemDimension(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id == 0) 0 else context.resources.getDimensionPixelSize(id)
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    private companion object {
        const val TAG = "ReadAloudFloating"
        const val SHADOW_PADDING_DP = 12
        const val SIDE_MARGIN_DP = 10
        const val BOTTOM_MARGIN_DP = 20
    }
}
