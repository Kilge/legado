package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.SeekBar
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
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
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
    private val callBack: CallBack get() = activity as CallBack
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
    }

    private fun initView() = binding.run {
        initAnimation()
        val textColor = context.primaryTextColor
        val secondaryTextColor = context.secondaryTextColor
        tvChapterName.setTextColor(secondaryTextColor)
        tvChapterUrl.setTextColor(secondaryTextColor)
        tvPre.setTextColor(textColor)
        tvNext.setTextColor(textColor)
        tvBrightnessLabel.setTextColor(textColor)
        tvQuickAutoPageLabel.setTextColor(secondaryTextColor)
        tvQuickNightThemeLabel.setTextColor(secondaryTextColor)
        tvCatalog.setTextColor(textColor)
        tvFont.setTextColor(textColor)
        tvSetting.setTextColor(textColor)
        fabAutoPage.setColorFilter(textColor)
        fabNightTheme.setColorFilter(textColor)
        ivCatalog.setColorFilter(textColor)
        ivFont.setColorFilter(textColor)
        ivSetting.setColorFilter(textColor)
        if (AppConfig.isNightTheme) {
            tvQuickNightThemeLabel.text = context.getString(R.string.theme_day)
            fabNightTheme.contentDescription = context.getString(R.string.theme_day)
            fabNightTheme.setImageResource(R.drawable.ic_daytime)
        } else {
            tvQuickNightThemeLabel.text = context.getString(R.string.theme_night)
            fabNightTheme.contentDescription = context.getString(R.string.theme_night)
            fabNightTheme.setImageResource(R.drawable.ic_brightness)
        }
        upBrightnessSectionVisibility()
        upBrightnessState()
        upMangaIcons()
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
        upBrightnessState()
        upMangaIcons()
        if (anim) {
            binding.titleBar.startAnimation(menuTopIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
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

        tvNext.setOnClickListener {
            ReadManga.moveToNextChapter(true)
        }
        tvPre.setOnClickListener {
            ReadManga.moveToPrevChapter(true)
        }
        seekReadPage.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    callBack.skipToPage(seekBar.progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
            }
        })
        //亮度跟随
        ivBrightnessAuto.setOnClickListener {
            context.putPrefBoolean("brightnessAuto", !brightnessAuto())
            upBrightnessState()
        }
        //亮度调节
        seekBrightness.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setScreenBrightness(progress.toFloat())
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppConfig.readBrightness = seekBar.progress
            }

        })
        //自动翻页
        llFabAutoPage.setOnClickListener {
            runMenuOut()
            callBack.autoPage()
        }
        //滤镜
        llFabColorFilter.setOnClickListener {
            callBack.openColorFilter()
        }
        //灰度模式
        llFabGray.setOnClickListener {
            callBack.toggleGray()
        }
        //墨水屏模式
        llFabEpaper.setOnClickListener {
            callBack.toggleEInk()
        }
        //主题切换(夜间模式)
        llFabNightTheme.setOnClickListener {
            AppConfig.isNightTheme = !AppConfig.isNightTheme
            ThemeConfig.applyDayNight(context)
        }
        //目录
        llCatalog.setOnClickListener {
            callBack.openCatalog()
        }
        //界面
        llFont.setOnClickListener {
            callBack.showInterfaceSetting()
        }
        //设置(更多选项)
        llSetting.setOnClickListener {
            callBack.showMoreSettingMenu()
        }
    }

    /**
     * 自动翻页图标状态
     */
    fun setAutoPage(autoPage: Boolean) = binding.run {
        if (autoPage) {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page_stop)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page_stop)
        } else {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page)
        }
        fabAutoPage.setColorFilter(context.primaryTextColor)
    }

    /**
     * 灰度/墨水屏图标两态(未选中线性/选中实心,选中强调色);滤镜启用时强调色
     */
    fun upMangaIcons(filterOn: Boolean = AppConfig.mangaColorFilter.orEmpty().isNotBlank()) = binding.run {
        val textColor = context.primaryTextColor
        val grayOn = AppConfig.enableMangaGray
        val eInkOn = AppConfig.enableMangaEInk
        fabGray.setImageResource(
            if (grayOn) R.drawable.ic_grayscale_filled else R.drawable.ic_grayscale_outline
        )
        fabGray.setColorFilter(if (grayOn) context.accentColor else textColor)
        fabEpaper.setImageResource(
            if (eInkOn) R.drawable.ic_book_filled else R.drawable.ic_book_outline
        )
        fabEpaper.setColorFilter(if (eInkOn) context.accentColor else textColor)
        fabColorFilter.setColorFilter(if (filterOn) context.accentColor else textColor)
    }

    /**
     * 亮度调整状态(与小说阅读菜单一致)
     */
    private fun brightnessAuto(): Boolean {
        return context.getPrefBoolean("brightnessAuto", true)
    }

    fun upBrightnessState() = binding.run {
        seekBrightness.progress = AppConfig.readBrightness
        if (brightnessAuto()) {
            ivBrightnessAuto.setColorFilter(context.accentColor)
            seekBrightness.isEnabled = false
        } else {
            ivBrightnessAuto.setColorFilter(context.buttonDisabledColor)
            seekBrightness.isEnabled = true
        }
    }

    /**
     * 设置屏幕亮度
     */
    private fun setScreenBrightness(value: Float) {
        activity?.run {
            val params = window.attributes
            val brightness = if (value < 1f) 0.004f else value / 255f
            params.screenBrightness = brightness
            window.attributes = params
        }
    }

    /**
     * 亮度调节控件显示(移植小说showBrightnessView)
     */
    fun upBrightnessSectionVisibility() {
        binding.llBrightness.isVisible =
            context.getPrefBoolean(PreferKey.showBrightnessView, true)
    }

    fun upSeekBar(value: Int, count: Int) {
        binding.seekReadPage.apply {
            max = count.minus(1)
            progress = value
        }
    }

    interface CallBack {
        fun openBookInfoActivity()
        fun openMangaConfig()
        fun upSystemUiVisibility(menuIsVisible: Boolean)
        fun skipToPage(index: Int)
        fun autoPage()
        fun openColorFilter()
        fun toggleGray()
        fun toggleEInk()
        fun openCatalog()
        fun showInterfaceSetting()
        fun showMoreSettingMenu()
    }

}
