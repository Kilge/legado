package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.config.applyReaderBottomSheetWindow
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent

/**
 * 漫画设置弹窗(同小说MoreConfigDialog:底部sheet+圆角面板+preference列表)
 */
class MangaMoreConfigDialog : BasePrefDialogFragment() {
    private val readPreferTag = "mangaReadPreferenceFragment"

    override fun onStart() {
        super.onStart()
        dialog?.window?.applyReaderBottomSheetWindow(
            height = minOf(
                (resources.displayMetrics.heightPixels * 0.68f).toInt(),
                520.dpToPx()
            ).coerceAtLeast(360.dpToPx())
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext()).apply {
            background = requireContext().dialogSurfaceBackground
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            id = R.id.tag1
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readPreferTag)
        if (preferenceFragment == null) preferenceFragment = MangaReadPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readPreferTag)
            .commit()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        //通知漫画页刷新菜单/图标状态
        postEvent(EventBus.UP_MANGA_CONFIG, AppConfig.mangaFooterConfig)
    }

    class MangaReadPreferenceFragment : ComposeSettingFragment() {

        override val titleRes: Int = R.string.setting

        override val applyActivityTitle: Boolean = false

        override val autoOpenTargetItem: Boolean = false

        override val drawPanelImage: Boolean = false

        override fun buildPageSpec(): SettingPageSpec {
            return SettingPageSpec(
                titleRes = titleRes,
                sections = listOf(
                    SettingSectionSpec(
                        title = getString(R.string.read_config),
                        items = listOf(
                            choice(
                                key = PreferKey.screenOrientation,
                                title = getString(R.string.screen_direction),
                                entriesRes = R.array.screen_direction_title,
                                valuesRes = R.array.screen_direction_value,
                                defaultValue = "0"
                            ),
                            choice(
                                key = PreferKey.keepLight,
                                title = getString(R.string.keep_light),
                                entriesRes = R.array.screen_time_out,
                                valuesRes = R.array.screen_time_out_value,
                                defaultValue = "0"
                            ),
                            switch(
                                key = PreferKey.showBrightnessView,
                                title = getString(R.string.show_brightness_view),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.hideMangaTitle,
                                title = getString(R.string.hide_manga_title),
                                defaultValue = false
                            )
                        )
                    ),
                    SettingSectionSpec(
                        title = getString(R.string.manga_config),
                        items = listOf(
                            switch(
                                key = PreferKey.disableMangaScale,
                                title = getString(R.string.disable_manga_scale),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.disableClickScroll,
                                title = getString(R.string.disable_manga_click_scroll),
                                defaultValue = false
                            ),
                            switch(
                                key = PreferKey.enableMangaEInk,
                                title = getString(R.string.manga_epaper),
                                defaultValue = false
                            ),
                            switch(
                                key = PreferKey.enableMangaGray,
                                title = getString(R.string.enable_manga_gray),
                                defaultValue = false
                            )
                        )
                    )
                )
            )
        }

        override fun onSettingPreferenceChanged(key: String) {
            when (key) {
                PreferKey.screenOrientation -> {
                    (activity as? ReadMangaActivity)?.setOrientation()
                }

                else -> {
                    //灰度/墨水屏/缩放/点击滚动/隐藏标题等,通知漫画页应用
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0))
                }
            }
        }

        private fun switch(
            key: String,
            title: String,
            defaultValue: Boolean,
            visible: Boolean = true
        ): SettingSwitchSpec {
            return SettingSwitchSpec(
                key = key,
                title = title,
                checked = booleanSetting(key, defaultValue),
                visible = visible,
                onCheckedChange = { updateBooleanSetting(key, it) }
            )
        }

        private fun choice(
            key: String,
            title: String,
            entriesRes: Int,
            valuesRes: Int,
            defaultValue: String
        ): SettingChoiceSpec {
            val options = choiceOptions(entriesRes, valuesRes)
            val selectedValue = stringSetting(key, defaultValue)
            return SettingChoiceSpec(
                key = key,
                title = title,
                summary = choiceLabel(options, selectedValue),
                options = options,
                selectedValue = selectedValue,
                onSelected = { updateStringSetting(key, it) }
            )
        }

        private fun choiceOptions(
            entriesRes: Int,
            valuesRes: Int
        ): List<SettingChoiceOption> {
            val entries = resources.getStringArray(entriesRes)
            val values = resources.getStringArray(valuesRes)
            return values.mapIndexed { index, value ->
                SettingChoiceOption(
                    value = value,
                    label = entries.getOrElse(index) { value }
                )
            }
        }

        private fun choiceLabel(
            options: List<SettingChoiceOption>,
            selectedValue: String
        ): String {
            return options.firstOrNull { it.value == selectedValue }
                ?.label
                ?.toString()
                ?: selectedValue
        }
    }
}
