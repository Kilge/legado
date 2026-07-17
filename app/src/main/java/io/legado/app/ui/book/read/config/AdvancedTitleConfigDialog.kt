package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiInputStyle
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.applyUiSubtleButtonStyle
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdvancedTitleConfigDialog : DialogFragment() {

    companion object {
        fun rulesOnly() = AdvancedTitleConfigDialog()
    }

    private val currentBook: Book?
        get() = ReadBook.book

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (resources.displayMetrics.heightPixels * 0.72f).toInt()
            )
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val book = currentBook
        val globalRule = AdvancedTitleConfig.globalRule
        val bookRule = AdvancedTitleConfig.bookRule(book)
        val startRule = bookRule ?: globalRule
        val emptyText = getString(R.string.empty)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dpToPx(), 12.dpToPx(), 18.dpToPx(), 4.dpToPx())
        }

        fun label(value: String) = TextView(context).apply {
            text = value
            setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            applyUiLabelStyle(context)
        }

        fun edit(value: String) = EditText(context).apply {
            setText(value)
            applyUiInputStyle(context, 1)
        }

        fun button(value: String) = TextView(context).apply {
            text = value
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_book_info_subtle_button)
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
            applyUiSubtleButtonStyle(context)
        }

        val scopeGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val globalButton = RadioButton(context).apply {
            text = getString(R.string.advanced_title_scope_global)
            id = View.generateViewId()
            typeface = context.uiTypeface()
        }
        val bookButton = RadioButton(context).apply {
            text = getString(R.string.advanced_title_scope_book)
            id = View.generateViewId()
            isEnabled = book != null
            typeface = context.uiTypeface()
        }
        scopeGroup.addView(globalButton)
        scopeGroup.addView(bookButton)
        scopeGroup.check(if (bookRule != null) bookButton.id else globalButton.id)

        val regexCheck = CheckBox(context).apply {
            text = getString(R.string.advanced_title_use_regex)
            isChecked = startRule.mode == AdvancedTitleConfig.SPLIT_REGEX
            typeface = context.uiTypeface()
        }
        val ruleEdit = edit(
            if (startRule.mode == AdvancedTitleConfig.SPLIT_REGEX) startRule.regex
            else startRule.delimiter
        )
        val sampleEdit = edit(getString(R.string.advanced_title_sample_default))
        val heightEdit = edit(AdvancedTitleConfig.heightFactor.toString()).apply {
            hint = getString(R.string.advanced_title_height_factor_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val preview = TextView(context).apply {
            setPadding(0, 8.dpToPx(), 0, 0)
            applyUiSectionTitleStyle(context)
        }

        fun buildRule() = AdvancedTitleConfig.SplitRule(
            mode = if (regexCheck.isChecked) {
                AdvancedTitleConfig.SPLIT_REGEX
            } else {
                AdvancedTitleConfig.SPLIT_DELIMITER
            },
            delimiter = if (regexCheck.isChecked) startRule.delimiter
            else ruleEdit.text?.toString().orEmpty(),
            regex = if (regexCheck.isChecked) ruleEdit.text?.toString().orEmpty()
            else startRule.regex
        )

        fun updatePreview() {
            preview.text = runCatching {
                val parts = AdvancedTitleConfig.split(
                    sampleEdit.text?.toString().orEmpty(),
                    buildRule()
                )
                getString(
                    R.string.advanced_title_preview_template,
                    parts.s1.ifBlank { emptyText },
                    parts.s2.ifBlank { emptyText }
                )
            }.getOrElse {
                getString(R.string.advanced_title_rule_error, it.localizedMessage.orEmpty())
            }
        }

        listOf(ruleEdit, sampleEdit).forEach { field ->
            field.doAfterTextChanged { updatePreview() }
        }
        regexCheck.setOnCheckedChangeListener { _, checked ->
            ruleEdit.setText(if (checked) startRule.regex else startRule.delimiter)
            ruleEdit.setSelection(ruleEdit.text?.length ?: 0)
            updatePreview()
        }

        root.addView(TextView(context).apply {
            text = getString(R.string.advanced_title_rule_settings)
            textSize = 18f
            applyUiTitleTypeface(context)
            setPadding(0, 2.dpToPx(), 0, 8.dpToPx())
        })
        root.addView(label(getString(R.string.advanced_title_scope_label)))
        root.addView(scopeGroup)
        root.addView(label(getString(R.string.advanced_title_rule_label)))
        root.addView(regexCheck)
        root.addView(ruleEdit)
        root.addView(label(getString(R.string.preview)))
        root.addView(sampleEdit)
        root.addView(preview)
        root.addView(label(getString(R.string.advanced_title_height_factor_label)))
        root.addView(heightEdit)
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply { topMargin = 12.dpToPx() }
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
        })
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12.dpToPx(), 0, 6.dpToPx())
            addView(button(getString(R.string.restore_default)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = 6.dpToPx() }
                setOnClickListener {
                    AdvancedTitleConfig.globalRule = AdvancedTitleConfig.SplitRule()
                    AdvancedTitleConfig.heightFactor = AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
                    book?.let {
                        AdvancedTitleConfig.setBookRule(it, null)
                        lifecycleScope.launch { withContext(Dispatchers.IO) { it.save() } }
                    }
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5, 8))
                    dismissAllowingStateLoss()
                }
            })
            addView(button(getString(R.string.cancel)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { dismissAllowingStateLoss() }
            })
            addView(button(getString(R.string.confirm)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 6.dpToPx() }
                setOnClickListener {
                    val rule = buildRule()
                    AdvancedTitleConfig.heightFactor = heightEdit.text?.toString()
                        ?.trim()
                        ?.toIntOrNull()
                        ?.coerceIn(30, 120)
                        ?: AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
                    if (scopeGroup.checkedRadioButtonId == bookButton.id && book != null) {
                        AdvancedTitleConfig.setBookRule(book, rule)
                        lifecycleScope.launch { withContext(Dispatchers.IO) { book.save() } }
                    } else {
                        AdvancedTitleConfig.globalRule = rule
                    }
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5, 8))
                    dismissAllowingStateLoss()
                }
            })
        })

        updatePreview()

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val container = CardView(context).apply {
            radius = 16.dpToPx().toFloat()
            cardElevation = 0f
            preventCornerOverlap = false
            useCompatPadding = false
            background = context.dialogSurfaceBackground
            addView(
                scroll,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        container.applyUiBodyTypefaceDeep(context.uiTypeface())
        return AlertDialog.Builder(context).setView(container).create()
    }
}
