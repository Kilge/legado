package io.legado.app.service

import android.app.PendingIntent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.LogUtils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private data class UtteranceRef(
        val sessionId: Long,
        val generation: Long,
        val cueIndex: Int
    )

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private var pendingPlayOnInit = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val utteranceGeneration = AtomicLong(0L)
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val engine = resolveSystemTtsEngine(ReadAloud.speechRoute.engineValue)
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    private fun resolveSystemTtsEngine(engineValue: String): String? {
        val value = engineValue.trim()
        if (value.isBlank()) return null
        return runCatching {
            JSONObject(value).optString("value").takeIf { it.isNotBlank() }
        }.getOrNull() ?: value
    }

    @Synchronized
    fun clearTTS() {
        speakJob?.cancel()
        speakJob = null
        pendingPlayOnInit = false
        utteranceGeneration.incrementAndGet()
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                if (pendingPlayOnInit) {
                    play()
                }
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (!ttsInitFinish) {
            pendingPlayOnInit = true
            return
        }
        pendingPlayOnInit = false
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            pauseReadAloud(abandonFocus = false)
            postReadAloudPlaybackPhase(
                ReadAloudPlaybackState.PHASE_PAUSED,
                message = "朗读内容为空",
                playing = false
            )
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        val sessionId = currentReadAloudSessionId
        val generation = utteranceGeneration.incrementAndGet()
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
            val contentList = contentList
            var isAddedText = false
            for (i in nowSpeak until contentList.size) {
                ensureActive()
                if (!isCurrentReadAloudSession(sessionId) ||
                    utteranceGeneration.get() != generation
                ) {
                    return@execute
                }
                var text = contentList[i]
                if (paragraphStartPos > 0 && i == nowSpeak) {
                    text = text.substring(paragraphStartPos)
                }
                if (text.matches(AppPattern.notReadAloudRegex)) {
                    continue
                }
                if (!isAddedText) {
                    val result = tts.runCatching {
                        speak(
                            text,
                            TextToSpeech.QUEUE_FLUSH,
                            ttsParamsForCue(i),
                            utteranceId(sessionId, generation, i)
                        )
                    }.getOrElse {
                        AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                        TextToSpeech.ERROR
                    }
                    if (result == TextToSpeech.ERROR) {
                        AppLog.put("tts出错 尝试重新初始化")
                        clearTTS()
                        initTts()
                        return@execute
                    }
                } else {
                    val result = tts.runCatching {
                        speak(
                            text,
                            TextToSpeech.QUEUE_ADD,
                            ttsParamsForCue(i),
                            utteranceId(sessionId, generation, i)
                        )
                    }.getOrElse {
                        AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                        TextToSpeech.ERROR
                    }
                    if (result == TextToSpeech.ERROR) {
                        AppLog.put("tts朗读出错:$text")
                    }
                }
                isAddedText = true
            }
            LogUtils.d(TAG, "朗读内容添加完成")
            if (!isAddedText &&
                isCurrentReadAloudSession(sessionId) &&
                utteranceGeneration.get() == generation
            ) {
                playStop()
                val stopGeneration = utteranceGeneration.get()
                lifecycleScope.launch {
                    delay(1000)
                    if (isCurrentReadAloudSession(sessionId) &&
                        utteranceGeneration.get() == stopGeneration &&
                        !pause
                    ) {
                        nextChapter()
                    }
                }
            }
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun ttsParamsForCue(cueIndex: Int): Bundle {
        return Bundle().apply {
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                speakerLoudnessInfo(cueIndex).gain.coerceIn(0f, 1f)
            )
        }
    }

    private fun utteranceId(sessionId: Long, generation: Long, cueIndex: Int): String =
        "${AppConst.APP_TAG}|$sessionId|$generation|$cueIndex"

    override fun playStop() {
        speakJob?.cancel()
        speakJob = null
        pendingPlayOnInit = false
        utteranceGeneration.incrementAndGet()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        playStop()
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        if (resumeBlockedReadAloudIfNeeded()) return
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        private fun utteranceRef(utteranceId: String?): UtteranceRef? {
            val parts = utteranceId
                ?.removePrefix("${AppConst.APP_TAG}|")
                ?.split('|')
                ?: return null
            if (parts.size != 3) return null
            val ref = UtteranceRef(
                sessionId = parts[0].toLongOrNull() ?: return null,
                generation = parts[1].toLongOrNull() ?: return null,
                cueIndex = parts[2].toIntOrNull() ?: return null
            )
            return ref.takeIf {
                isCurrentReadAloudSession(it.sessionId) &&
                        utteranceGeneration.get() == it.generation &&
                        it.cueIndex in contentList.indices
            }
        }

        override fun onStart(s: String) {
            val cueIndex = utteranceRef(s)?.cueIndex ?: return
            if (pause) return
            if (cueIndex != nowSpeak) {
                syncToCueIndex(cueIndex)
            }
            val cueStartOffset = paragraphStartPos.takeIf { cueIndex == nowSpeak } ?: 0
            postReadAloudPlaybackPhase(
                ReadAloudPlaybackState.PHASE_PLAYING,
                cueIndex = cueIndex
            )
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            textChapter?.let {
                if (contentList.getOrNull(nowSpeak)?.matches(AppPattern.notReadAloudRegex) == true) {
                    nextParagraph()
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + cueStartOffset + 1 > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage(fromReadAloud = true)
                }
                upTtsProgress(readAloudNumber + cueStartOffset + 1)
            }
        }

        override fun onDone(s: String) {
            LogUtils.d(TAG, "onDone utteranceId:$s")
            val cueIndex = utteranceRef(s)?.cueIndex ?: return
            if (!pause && cueIndex == nowSpeak) {
                nextParagraph()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            val cueIndex = utteranceRef(utteranceId)?.cueIndex ?: return
            if (pause) return
            if (cueIndex != nowSpeak) {
                syncToCueIndex(cueIndex)
            }
            val cueStartOffset = paragraphStartPos.takeIf { cueIndex == nowSpeak } ?: 0
            val msg =
                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            textChapter?.let {
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + cueStartOffset + start > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage(fromReadAloud = true)
                    upTtsProgress(readAloudNumber + cueStartOffset + start)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val cueIndex = utteranceRef(utteranceId)?.cueIndex ?: return
            postReadAloudPlaybackPhase(
                ReadAloudPlaybackState.PHASE_ERROR,
                cueIndex = cueIndex,
                message = "TTS错误 $errorCode"
            )
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
            if (!pause && cueIndex == nowSpeak) {
                nextParagraph()
            }
        }

        private fun nextParagraph() {
            //跳过全标点段落
            if (!moveToNextCue()) {
                nextChapter()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            val cueIndex = utteranceRef(s)?.cueIndex ?: return
            postReadAloudPlaybackPhase(
                ReadAloudPlaybackState.PHASE_ERROR,
                cueIndex = cueIndex,
                message = "TTS错误"
            )
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            if (!pause && cueIndex == nowSpeak) {
                nextParagraph()
            }
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
