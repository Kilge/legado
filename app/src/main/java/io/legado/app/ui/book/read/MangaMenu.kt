package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.Animation
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ViewMangaMenuBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.buttonDisabledColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.ui.book.manga.config.MangaAutoReadDialog
import io.legado.app.ui.book.read.config.rememberReaderMenuDialogStyle
import io.legado.app.ui.book.read.ReadMenuSeekBarRow
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible

class MangaMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val binding = ViewMangaMenuBinding.inflate(LayoutInflater.from(context), this, true)
    internal val callBack: CallBack get() = activity as CallBack
    var canShowMenu: Boolean = false
    private val menuTopIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    private val menuTopOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    private val menuBottomIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_in)
    }
    private val menuBottomOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_out)
    }
    private var isMenuOutAnimating = false
    private var bgColor = context.bottomBackground

    /**
     * 当前章节进度(Compose章节条读取)
     */
    var seekProgress: Int = 0
    var seekMax: Int = 0

    /**
     * Compose快捷面板刷新信号(供setAutoPage/upMangaIcons等外部更新时触发重组)
     */
    private val refreshState = mutableIntStateOf(0)

    private val menuOutListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isMenuOutAnimating = true
            binding.vwMenuBg.setOnClickListener(null)
        }

        override fun onAnimationEnd(animation: Animation) {
            this@MangaMenu.invisible()
            binding.titleBar.invisible()
            binding.bottomMenu.invisible()
            isMenuOutAnimating = false
            canShowMenu = false
            callBack.upSystemUiVisibility(false)
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }
    private val menuInListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            binding.tvSourceAction.text =
                ReadManga.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
            callBack.upSystemUiVisibility(true)
            binding.tvSourceAction.isGone = false
        }

        @SuppressLint("RtlHardcoded")
        override fun onAnimationEnd(animation: Animation) {
            binding.run {
                vwMenuBg.setOnClickListener { runMenuOut() }
            }
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }

    init {
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())
        initView()
        bindEvent()
        initComposeQuickActions()
    }

    private fun initView() = binding.run {
        initAnimation()
        val textColor = context.primaryTextColor
        val secondaryTextColor = context.secondaryTextColor
        tvChapterName.setTextColor(secondaryTextColor)
        tvChapterUrl.setTextColor(secondaryTextColor)
        if (AppConfig.isEInkMode) {
            titleBar.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            bottomMenu.setBackgroundResource(R.drawable.bg_eink_border_top)
        } else {
            bottomMenu.setBackgroundColor(Color.TRANSPARENT)
        }
        if (AppConfig.showReadTitleBarAddition) {
            titleBarAddition.visible()
        } else {
            titleBarAddition.gone()
        }
        /**
         * 确保视图不被导航栏遮挡
         */
        bottomMenu.applyNavigationBarPadding()
    }

    private fun initAnimation() {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    private fun initComposeQuickActions() {
        binding.quickActionsCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val refresh by refreshState
                QuickActionsPanel(
                    menu = this@MangaMenu,
                    refresh = refresh,
                    onAutoPage = { callBack.autoPage() },
                    onOpenColorFilter = { callBack.openColorFilter() },
                    onToggleGray = { callBack.toggleGray() },
                    onToggleEInk = { callBack.toggleEInk() },
                    onToggleNightTheme = { callBack.toggleNightTheme() },
                    onOpenCatalog = { callBack.openCatalog() },
                    onShowInterfaceSetting = { callBack.showInterfaceSetting() },
                    onShowMoreSetting = { callBack.showMoreSettingMenu() }
                )
            }
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode) {
        if (isMenuOutAnimating) {
            return
        }
        if (this.isVisible) {
            if (anim) {
                binding.titleBar.startAnimation(menuTopOut)
                binding.bottomMenu.startAnimation(menuBottomOut)
            } else {
                menuOutListener.onAnimationStart(menuBottomOut)
                menuOutListener.onAnimationEnd(menuBottomOut)
            }
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        this.visible()
        binding.titleBar.visible()
        binding.bottomMenu.visible()
        refreshQuickActions()
        if (anim) {
            binding.titleBar.startAnimation(menuTopIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
    }

    /**
     * 触发Compose快捷面板重组(自动翻页图标/灰度墨水屏两态/亮度状态)
     */
    fun refreshQuickActions() {
        refreshState.value++
    }

    /**
     * 自动翻页图标状态(切换ic_auto_page_stop/ic_auto_page,Compose面板经refreshState感知)
     */
    fun setAutoPage(autoPage: Boolean) {
        refreshState.value++
    }

    /**
     * 灰度/墨水屏/滤镜图标状态刷新(Compose面板经refreshState感知)
     */
    fun upMangaIcons(filterOn: Boolean = AppConfig.mangaColorFilter.orEmpty().isNotBlank()) {
        refreshState.value++
    }

    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
        titleBar.toolbar.setOnClickListener {
            callBack.openBookInfoActivity()
        }
        val chapterViewClickListener = OnClickListener {
            val url = tvChapterUrl.text.toString().trim()
            if (url.isBlank()) return@OnClickListener
            context.startActivity<WebViewActivity> {
                val bookSource = ReadBook.bookSource
                putExtra("title", tvChapterName.text)
                putExtra("url", url)
                putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                putExtra("sourceName", bookSource?.bookSourceName)
                putExtra("sourceType", bookSource?.getSourceType())
            }
        }
        val chapterViewLongClickListener = OnLongClickListener {
            val url = tvChapterUrl.text.toString().trim()
            if (url.isNotBlank()) {
                context.alert(R.string.open_fun) {
                    setMessage(R.string.use_browser_open)
                    okButton {
                        context.openUrl(url)
                    }
                    noButton()
                }
            }
            true
        }
        tvChapterName.setOnClickListener(chapterViewClickListener)
        tvChapterName.setOnLongClickListener(chapterViewLongClickListener)
        tvChapterUrl.setOnClickListener(chapterViewClickListener)
        tvChapterUrl.setOnLongClickListener(chapterViewLongClickListener)
    }

    fun upSeekBar(value: Int, count: Int) {
        seekProgress = value
        seekMax = count.minus(1)
        refreshQuickActions()
    }

    interface CallBack {
        fun openBookInfoActivity()
        fun openMangaConfig()
        fun upSystemUiVisibility(menuIsVisible: Boolean)
        fun skipToPage(index: Int)
        fun autoPage()
        fun isAutoPageActive(): Boolean
        fun openColorFilter()
        fun toggleGray()
        fun toggleEInk()
        fun toggleNightTheme()
        fun openCatalog()
        fun showInterfaceSetting()
        fun showMoreSettingMenu()
    }

}

/**
 * 漫画菜单快捷面板(Compose,复用Archive小说阅读菜单样式:章节条+亮度行+图标按钮网格)
 */
@Composable
private fun QuickActionsPanel(
    menu: MangaMenu,
    refresh: Int,
    onAutoPage: () -> Unit,
    onOpenColorFilter: () -> Unit,
    onToggleGray: () -> Unit,
    onToggleEInk: () -> Unit,
    onToggleNightTheme: () -> Unit,
    onOpenCatalog: () -> Unit,
    onShowInterfaceSetting: () -> Unit,
    onShowMoreSetting: () -> Unit
) {
    val context = LocalContext.current
    val style = rememberReaderMenuDialogStyle(context.bottomBackground)
    var brightness by remember(refresh) { mutableIntStateOf(AppConfig.readBrightness) }
    var brightnessAuto by remember(refresh) { mutableStateOf(context.getPrefBoolean("brightnessAuto", true)) }
    var autoPageActive by remember(refresh) { mutableStateOf(menu.callBack.isAutoPageActive()) }
    var grayOn by remember(refresh) { mutableStateOf(AppConfig.enableMangaGray) }
    var eInkOn by remember(refresh) { mutableStateOf(AppConfig.enableMangaEInk) }
    var filterOn by remember(refresh) { mutableStateOf(AppConfig.mangaColorFilter.orEmpty().isNotBlank()) }
    var nightTheme by remember(refresh) { mutableStateOf(AppConfig.isNightTheme) }

    fun setScreenBrightness(value: Float) {
        menu.activity?.run {
            val params = window.attributes
            val b = if (value < 1f) 0.004f else value / 255f
            params.screenBrightness = b
            window.attributes = params
        }
    }

    // 整体面板(同小说阅读菜单底部面板样式)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = style.panelRadius,
            topEnd = style.panelRadius
        ),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 章节条(同小说 ReadMenuSeekBarRow)
            ReadMenuSeekBarRow(
                seekProgress = menu.seekProgress,
                seekMax = menu.seekMax,
                canGoPrev = menu.seekProgress > 0,
                canGoNext = menu.seekProgress < menu.seekMax,
                style = style,
                onPrevClick = { ReadManga.moveToPrevChapter(true) },
                onNextClick = { ReadManga.moveToNextChapter(true) },
                onSeekStart = { },
                onSeekStop = { progress -> menu.callBack.skipToPage(progress) }
            )

            // 亮度行
            BrightnessRow(
                brightness = brightness,
                isAuto = brightnessAuto,
                style = style,
                onAutoClick = {
                    brightnessAuto = !brightnessAuto
                    context.putPrefBoolean("brightnessAuto", brightnessAuto)
                },
                onBrightnessChange = {
                    brightness = it
                    setScreenBrightness(it.toFloat())
                },
                onBrightnessStop = {
                    brightness = it
                    AppConfig.readBrightness = it
                }
            )

            // 快捷按钮第一行:滤镜/灰度/墨水屏/夜间
            QuickButtonRow {
                QuickActionButton(
                    title = stringResource(R.string.manga_color_filter),
                    iconRes = R.drawable.ic_filter_filled,
                    active = filterOn,
                    style = style,
                    onClick = onOpenColorFilter
                )
                QuickActionButton(
                    title = stringResource(R.string.enable_manga_gray),
                    iconRes = if (grayOn) R.drawable.ic_grayscale_filled else R.drawable.ic_grayscale_outline,
                    active = grayOn,
                    style = style,
                    onClick = {
                        grayOn = !grayOn
                        onToggleGray()
                    }
                )
                QuickActionButton(
                    title = stringResource(R.string.manga_epaper),
                    iconRes = if (eInkOn) R.drawable.ic_book_filled else R.drawable.ic_book_outline,
                    active = eInkOn,
                    style = style,
                    onClick = {
                        eInkOn = !eInkOn
                        onToggleEInk()
                    }
                )
                QuickActionButton(
                    title = stringResource(if (nightTheme) R.string.theme_day else R.string.theme_night),
                    iconRes = if (nightTheme) R.drawable.ic_daytime else R.drawable.ic_brightness,
                    active = nightTheme,
                    style = style,
                    onClick = {
                        nightTheme = !nightTheme
                        onToggleNightTheme()
                    }
                )
            }

            // 快捷按钮第二行:目录/自动翻页/界面/设置
            QuickButtonRow {
                QuickActionButton(
                    title = stringResource(R.string.chapter_list),
                    iconRes = R.drawable.ic_toc,
                    active = false,
                    style = style,
                    onClick = onOpenCatalog
                )
                QuickActionButton(
                    title = stringResource(if (autoPageActive) R.string.auto_next_page_stop else R.string.auto_next_page),
                    iconRes = if (autoPageActive) R.drawable.ic_auto_page_stop else R.drawable.ic_auto_page,
                    active = autoPageActive,
                    style = style,
                    onClick = {
                        autoPageActive = !autoPageActive
                        onAutoPage()
                    }
                )
                QuickActionButton(
                    title = stringResource(R.string.interface_setting),
                    iconRes = R.drawable.ic_interface_setting,
                    active = false,
                    style = style,
                    onClick = onShowInterfaceSetting
                )
                QuickActionButton(
                    title = stringResource(R.string.setting),
                    iconRes = R.drawable.ic_settings,
                    active = false,
                    style = style,
                    onClick = onShowMoreSetting
                )
            }
        }
    }
}

@Composable
private fun QuickButtonRow(
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        content()
    }
}

@Composable
private fun RowScope.QuickActionButton(
    title: String,
    iconRes: Int,
    active: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = if (active) style.accent else style.primaryText
    Column(
        modifier = modifier
            .weight(1f)
            .clip(RoundedCornerShape(style.actionRadius))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = textColor,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = title,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
        )
    }
}

@Composable
private fun BrightnessRow(
    brightness: Int,
    isAuto: Boolean,
    style: AppDialogStyle,
    onAutoClick: () -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onBrightnessStop: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.brightness),
            color = style.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        // 自动按钮
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(style.actionRadius))
                .clickable(onClick = onAutoClick)
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_brightness_auto),
                contentDescription = stringResource(R.string.brightness),
                tint = if (isAuto) style.accent else style.secondaryText,
                modifier = Modifier.size(20.dp)
            )
        }
        // 滑块
        AppThemedStepperSlider(
            value = brightness,
            range = 0..255,
            onValueChange = { onBrightnessChange(it) },
            palette = style.toMiuixPalette(),
            step = 1,
            enabled = !isAuto,
            onValueChangeFinished = { onBrightnessStop(brightness) },
            modifier = Modifier.weight(1f)
        )
    }
}
