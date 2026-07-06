package io.legado.app.lib.theme

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig

object ThemeRuntimeKeys {

    fun fontScale(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.fontScaleN else PreferKey.fontScale

    fun uiFontPath(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiFontPathN else PreferKey.uiFontPath

    fun titleFontPath(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.titleFontPathN else PreferKey.titleFontPath

    fun uiFontColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiFontColorN else PreferKey.uiFontColor

    fun titleFontColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.titleFontColorN else PreferKey.titleFontColor

    fun uiCornerScale(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiCornerScaleN else PreferKey.uiCornerScale

    fun uiLayoutAlpha(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiLayoutAlphaN else PreferKey.uiLayoutAlpha

    fun dialogAlpha(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.dialogAlphaN else PreferKey.dialogAlpha

    fun uiCornerSearchFollow(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiCornerSearchFollowN else PreferKey.uiCornerSearchFollow

    fun uiCornerReplyFollow(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiCornerReplyFollowN else PreferKey.uiCornerReplyFollow

    fun themeCardColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeCardColorN else PreferKey.themeCardColor

    fun themeMutedColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeMutedColorN else PreferKey.themeMutedColor

    fun themeSearchFieldBackgroundColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeSearchFieldBackgroundColorN else PreferKey.themeSearchFieldBackgroundColor

    fun themeTabBackgroundColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeTabBackgroundColorN else PreferKey.themeTabBackgroundColor

    fun themeShelfColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeShelfColorN else PreferKey.themeShelfColor

    fun themeCardShadow(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeCardShadowN else PreferKey.themeCardShadow

    fun themeCardBackgroundBlur(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeCardBackgroundBlurN else PreferKey.themeCardBackgroundBlur

    fun activeColorKey(key: String, isNight: Boolean = AppConfig.isNightTheme): String {
        return when (key) {
            PreferKey.themeCardColor, PreferKey.themeCardColorN -> themeCardColor(isNight)
            PreferKey.themeMutedColor, PreferKey.themeMutedColorN -> themeMutedColor(isNight)
            PreferKey.themeSearchFieldBackgroundColor,
            PreferKey.themeSearchFieldBackgroundColorN -> themeSearchFieldBackgroundColor(isNight)
            PreferKey.themeTabBackgroundColor, PreferKey.themeTabBackgroundColorN -> themeTabBackgroundColor(isNight)
            PreferKey.themeShelfColor, PreferKey.themeShelfColorN -> themeShelfColor(isNight)
            else -> key
        }
    }

    fun allKeys(): Set<String> = setOf(
        PreferKey.fontScale,
        PreferKey.fontScaleN,
        PreferKey.uiFontPath,
        PreferKey.uiFontPathN,
        PreferKey.titleFontPath,
        PreferKey.titleFontPathN,
        PreferKey.uiFontColor,
        PreferKey.uiFontColorN,
        PreferKey.titleFontColor,
        PreferKey.titleFontColorN,
        PreferKey.uiCornerScale,
        PreferKey.uiCornerScaleN,
        PreferKey.uiLayoutAlpha,
        PreferKey.uiLayoutAlphaN,
        PreferKey.dialogAlpha,
        PreferKey.dialogAlphaN,
        PreferKey.uiCornerSearchFollow,
        PreferKey.uiCornerSearchFollowN,
        PreferKey.uiCornerReplyFollow,
        PreferKey.uiCornerReplyFollowN,
        PreferKey.themeCardColor,
        PreferKey.themeCardColorN,
        PreferKey.themeMutedColor,
        PreferKey.themeMutedColorN,
        PreferKey.themeSearchFieldBackgroundColor,
        PreferKey.themeSearchFieldBackgroundColorN,
        PreferKey.themeTabBackgroundColor,
        PreferKey.themeTabBackgroundColorN,
        PreferKey.themeShelfColor,
        PreferKey.themeShelfColorN,
        PreferKey.themeCardShadow,
        PreferKey.themeCardShadowN,
        PreferKey.themeCardBackgroundBlur,
        PreferKey.themeCardBackgroundBlurN
    )
}
