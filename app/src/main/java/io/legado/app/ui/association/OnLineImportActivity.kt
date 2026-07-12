package io.legado.app.ui.association

import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络一键导入
 * 格式: legado://import/{path}?src={url}
 */
class OnLineImportActivity :
    VMBaseActivity<ActivityTranslucenceBinding, OnLineImportViewModel>() {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)
    override val viewModel by viewModels<OnLineImportViewModel>()
    private val onlineImportDownloader by lazy { OnlineImportDownloader(applicationContext) }
    private var pendingDownload: OnlineImportDownload? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(it.second, true)
                )
                "rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(it.second, true)
                )
                "replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(it.second, true)
                )
                "httpTts" -> showDialogFragment(
                    ImportHttpTtsDialog(it.second, true)
                )
                "theme" -> showDialogFragment(
                    ImportThemeDialog(it.second, true)
                )
                "txtRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(it.second, true)
                )
                "dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(it.second, true)
                )
            }
        }
        viewModel.errorLive.observe(this) {
            finallyDialog(getString(R.string.error), it)
        }
        intent.data?.let {
            val url = it.getQueryParameter("src")
            when (val route = OnlinePackageImportRoute.parse(it.scheme, it.host, it.path, url)) {
                is OnlinePackageImportRoute.ParagraphRule -> {
                    downloadOnlinePackage(route, OnlineImportPayloadType.PARAGRAPH_RULES)
                    return
                }

                is OnlinePackageImportRoute.Bubble -> {
                    downloadOnlinePackage(route, OnlineImportPayloadType.BUBBLE_PACKAGE)
                    return
                }

                is OnlinePackageImportRoute.Invalid -> {
                    finallyDialog(getString(R.string.error), route.reason)
                    return
                }

                OnlinePackageImportRoute.Other -> Unit
            }
            if (url.isNullOrEmpty()) {
                finish()
                return
            }
            when (it.path) {
                "/bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(url, true)
                )

                "/rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(url, true)
                )

                "/replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(url, true)
                )
                "/textTocRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(url, true)
                )
                "/httpTTS" -> showDialogFragment(
                    ImportHttpTtsDialog(url, true)
                )
                "/dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(url, true)
                )
                "/theme" -> showDialogFragment(
                    ImportThemeDialog(url, true)
                )
                "/readConfig" -> viewModel.getBytes(url) { bytes ->
                    viewModel.importReadConfig(bytes, this::finallyDialog)
                }
                "/addToBookshelf" -> showDialogFragment(
                    AddToBookshelfDialog(url, true)
                )
                "/importonline" -> when (it.host) {
                    "booksource" -> showDialogFragment(
                        ImportBookSourceDialog(url, true)
                    )
                    "rsssource" -> showDialogFragment(
                        ImportRssSourceDialog(url, true)
                    )
                    "replace" -> showDialogFragment(
                        ImportReplaceRuleDialog(url, true)
                    )
                    else -> {
                        viewModel.determineType(url, this::finallyDialog)
                    }
                }
                else -> viewModel.determineType(url, this::finallyDialog)
            }
        }
    }

    private fun downloadOnlinePackage(
        route: OnlinePackageImportRoute,
        payloadType: OnlineImportPayloadType,
        allowPrivateNetwork: Boolean = false
    ) {
        val sourceUrl = when (route) {
            is OnlinePackageImportRoute.ParagraphRule -> route.sourceUrl
            is OnlinePackageImportRoute.Bubble -> route.sourceUrl
            else -> return
        }
        lifecycleScope.launch {
            runCatching {
                onlineImportDownloader.download(sourceUrl, payloadType, allowPrivateNetwork)
            }.onSuccess { download ->
                if (isFinishing || isDestroyed) {
                    download.close()
                    return@onSuccess
                }
                pendingDownload?.close()
                pendingDownload = download
                showOnlineImportPreview(route, download)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is PrivateNetworkConfirmationRequiredException && !allowPrivateNetwork) {
                    showPrivateNetworkConfirmation(route, payloadType)
                } else {
                    finallyDialog(
                        getString(R.string.error),
                        error.localizedMessage ?: getString(R.string.unknown_error)
                    )
                }
            }
        }
    }

    private fun showPrivateNetworkConfirmation(
        route: OnlinePackageImportRoute,
        payloadType: OnlineImportPayloadType
    ) {
        var accepted = false
        alert(
            getString(R.string.online_import_private_network_title),
            getString(R.string.online_import_private_network_message)
        ) {
            positiveButton(R.string.continue_) {
                accepted = true
                downloadOnlinePackage(route, payloadType, allowPrivateNetwork = true)
            }
            cancelButton()
            onDismiss {
                if (!accepted) finish()
            }
        }
    }

    private fun showOnlineImportPreview(
        route: OnlinePackageImportRoute,
        download: OnlineImportDownload
    ) {
        val typeName = when (route) {
            is OnlinePackageImportRoute.ParagraphRule -> getString(R.string.paragraph_rule)
            is OnlinePackageImportRoute.Bubble -> getString(R.string.bubble_package)
            else -> return
        }
        val scriptWarning = if (route is OnlinePackageImportRoute.ParagraphRule) {
            "\n\n${getString(R.string.online_import_paragraph_script_warning)}"
        } else {
            ""
        }
        val message = getString(
            R.string.online_import_preview_message,
            typeName,
            download.sourceUrl,
            download.finalUrl,
            Formatter.formatFileSize(this, download.size),
            if (download.privateNetwork) getString(R.string.yes) else getString(R.string.no)
        ) + scriptWarning
        var accepted = false
        alert(getString(R.string.online_import_confirm_title), message) {
            positiveButton(R.string.import_) {
                accepted = true
                if (pendingDownload === download) pendingDownload = null
                importOnlinePackage(route, download)
            }
            cancelButton()
            onDismiss {
                if (!accepted) {
                    if (pendingDownload === download) pendingDownload = null
                    download.close()
                    finish()
                }
            }
        }
    }

    private fun importOnlinePackage(
        route: OnlinePackageImportRoute,
        download: OnlineImportDownload
    ) {
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                when (route) {
                    is OnlinePackageImportRoute.Bubble -> withContext(IO) {
                        BubblePackageManager.importZip(download.file)
                    }

                    is OnlinePackageImportRoute.ParagraphRule -> error(
                        getString(R.string.online_import_paragraph_not_ready)
                    )

                    else -> return@launch
                }
                finallyDialog(getString(R.string.success), getString(R.string.success))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                finallyDialog(
                    getString(R.string.error),
                    error.localizedMessage ?: getString(R.string.unknown_error)
                )
            } finally {
                download.close()
            }
        }
    }

    override fun onDestroy() {
        pendingDownload?.close()
        pendingDownload = null
        super.onDestroy()
    }

    private fun finallyDialog(title: String, msg: String) {
        alert(title, msg) {
            okButton()
            onDismiss {
                finish()
            }
        }
    }

}
