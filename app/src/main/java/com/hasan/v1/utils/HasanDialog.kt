package com.hasan.v1.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hasan.v1.R

/**
 * Dialogs dans la DA de l'app : fond #1A1A1A, texte blanc, boutons rouge accent.
 * Remplace les AlertDialog.Builder système (fond gris, thème clair).
 */
object HasanDialog {

    /** Dialog de confirmation simple (message + Confirmer/Annuler). */
    fun confirm(
        context: Context,
        title: String? = null,
        message: String,
        confirmLabel: String = "Confirmer",
        cancelLabel: String = "Annuler",
        destructive: Boolean = false,
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        show(context, title, message, null,
            confirmLabel, cancelLabel, destructive, onConfirm, onCancel)
    }

    /** Dialog de saisie de texte (titre + champ pré-rempli + Confirmer/Annuler). */
    fun input(
        context: Context,
        title: String,
        default: String = "",
        hint: String = "",
        confirmLabel: String = "Confirmer",
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            setText(default)
            setSelection(default.length)
            setHint(hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(ContextCompat.getColor(context, R.color.hasan_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.hasan_text_hint))
            background = null
            setPadding(0, 12, 0, 12)
        }
        show(context, title, null, input,
            confirmLabel, "Annuler", false,
            { onConfirm(input.text.toString().trim()) },
            null
        )
    }

    /** Dialog de liste d'options (titre + items). */
    fun list(
        context: Context,
        title: String,
        items: List<String>,
        onSelect: (Int) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        val root = buildRoot(context)
        addTitle(context, root, title)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.bottomMargin = dp(context, 4) }
            setBackgroundColor(ContextCompat.getColor(context, R.color.hasan_border))
        }
        root.addView(divider)

        items.forEachIndexed { index, label ->
            val item = TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.hasan_text_primary))
                setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(index)
                }
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            root.addView(item)
        }

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun show(
        context: Context,
        title: String?,
        message: String?,
        customView: View?,
        confirmLabel: String,
        cancelLabel: String,
        destructive: Boolean,
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)?
    ) {
        val dialog = Dialog(context)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        val root = buildRoot(context)

        if (title != null) addTitle(context, root, title)

        if (message != null) {
            root.addView(TextView(context).apply {
                text = message
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.hasan_text_secondary))
                setPadding(0, if (title != null) dp(context, 8) else 0, 0, dp(context, 16))
            })
        }

        if (customView != null) {
            val wrapper = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(context, 16) }
            customView.layoutParams = wrapper
            root.addView(customView)

            val underline = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.bottomMargin = dp(context, 16) }
                setBackgroundColor(ContextCompat.getColor(context, R.color.hasan_border))
            }
            root.addView(underline)
        }

        // Boutons
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnRow.addView(buildButton(context, cancelLabel, false) {
            dialog.dismiss()
            onCancel?.invoke()
        })

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 8), 0)
        }
        btnRow.addView(spacer)

        val confirmColor = if (destructive) R.color.hasan_accent else R.color.hasan_accent
        btnRow.addView(buildButton(context, confirmLabel, true, confirmColor) {
            dialog.dismiss()
            onConfirm()
        })

        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun buildRoot(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_dialog)
        setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 16))
    }

    private fun addTitle(context: Context, parent: LinearLayout, title: String) {
        parent.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.hasan_text_primary))
            setPadding(0, 0, 0, dp(context, 12))
        })
    }

    private fun buildButton(
        context: Context,
        label: String,
        filled: Boolean,
        colorRes: Int = R.color.hasan_accent,
        onClick: () -> Unit
    ) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = android.view.Gravity.CENTER
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        if (filled) {
            setTextColor(Color.WHITE)
            setBackgroundColor(ContextCompat.getColor(context, colorRes))
            // Coins arrondis sur fond plein
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, colorRes))
                cornerRadius = dp(context, 8).toFloat()
            }
        } else {
            setTextColor(ContextCompat.getColor(context, R.color.hasan_text_secondary))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, ContextCompat.getColor(context, R.color.hasan_border))
                cornerRadius = dp(context, 8).toFloat()
            }
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
