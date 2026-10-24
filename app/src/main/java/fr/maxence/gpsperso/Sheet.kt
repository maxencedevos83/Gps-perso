package fr.maxence.gpsperso

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Panneau moderne qui glisse depuis le bas de l'écran (style Google Maps) :
 * poignée, titre, lignes avec pastille d'icône, gros boutons arrondis.
 */
class Sheet(private val activity: Activity) {
    private val dialog = Dialog(activity, R.style.SheetDialog)
    private val density = activity.resources.displayMetrics.density
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(16))
    }
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
        }
    }

    init {
        // Poignée grise
        root.addView(View(activity).apply {
            background = rounded(Color.parseColor("#DADCE0"), dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
                bottomMargin = dp(6)
            }
        })
        root.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
    }

    fun dp(v: Int) = (v * density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    fun title(text: String, subtitle: String? = null): Sheet {
        content.addView(TextView(activity).apply {
            this.text = text
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, dp(6), 0, if (subtitle == null) dp(10) else dp(2))
        })
        if (subtitle != null) {
            content.addView(TextView(activity).apply {
                this.text = subtitle
                textSize = 14f
                setTextColor(Color.parseColor("#5F6368"))
                setPadding(0, 0, 0, dp(12))
            })
        }
        return this
    }

    fun text(text: String, color: String = "#3C4043", size: Float = 15f): Sheet {
        content.addView(TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            setPadding(0, dp(4), 0, dp(8))
            setLineSpacing(0f, 1.15f)
        })
        return this
    }

    fun sectionTitle(text: String): Sheet {
        content.addView(TextView(activity).apply {
            this.text = text.uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A73E8"))
            setPadding(0, dp(14), 0, dp(4))
        })
        return this
    }

    /** Ligne cliquable : pastille ronde colorée avec une icône, titre, sous-titre, chevron. */
    fun item(icon: String, title: String, subtitle: String? = null, color: String = "#E8F0FE", onClick: () -> Unit): Sheet {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = activity.getDrawable(android.R.drawable.list_selector_background)
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                onClick()
            }
        }
        row.addView(TextView(activity).apply {
            text = icon
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(color))
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        texts.addView(TextView(activity).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#202124"))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrBlank()) {
            texts.addView(TextView(activity).apply {
                text = subtitle
                textSize = 13.5f
                setTextColor(Color.parseColor("#5F6368"))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(activity).apply {
            text = "›"
            textSize = 24f
            setTextColor(Color.parseColor("#BDC1C6"))
        })
        content.addView(row)
        return this
    }

    /** Zone vide à remplir plus tard (infos chargées en arrière-plan). */
    fun slot(): LinearLayout {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(box)
        return box
    }

    /** Ligne d'information : icône, texte, action facultative au toucher. */
    fun infoRow(icon: String, text: String, onClick: (() -> Unit)? = null): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            if (onClick != null) {
                isClickable = true
                background = activity.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { onClick() }
            }
        }
        row.addView(TextView(activity).apply {
            this.text = icon
            textSize = 18f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(activity).apply {
            this.text = text
            textSize = 15.5f
            setLineSpacing(0f, 1.15f)
            setTextColor(Color.parseColor(if (onClick != null) "#1A73E8" else "#3C4043"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    fun view(v: View): Sheet {
        content.addView(v)
        return this
    }

    /** Champ de saisie arrondi. */
    fun input(hint: String, value: String = ""): EditText {
        val field = EditText(activity).apply {
            this.hint = hint
            setText(value)
            textSize = 16f
            isSingleLine = true
            background = rounded(Color.parseColor("#F1F3F4"), dp(14))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })
        return field
    }

    /** Gros bouton principal bleu, sur toute la largeur. */
    fun primary(text: String, color: String = "#1A73E8", onClick: () -> Unit): Sheet = button(text, color, Color.WHITE, onClick)

    /** Bouton secondaire bleu clair. */
    fun secondary(text: String, onClick: () -> Unit): Sheet =
        button(text, "#E8F0FE", Color.parseColor("#1A73E8"), onClick)

    private fun button(text: String, bg: String, fg: Int, onClick: () -> Unit): Sheet {
        content.addView(TextView(activity).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(fg)
            background = rounded(Color.parseColor(bg), dp(26))
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                onClick()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) })
        return this
    }

    fun onDismiss(action: () -> Unit): Sheet {
        dialog.setOnDismissListener { action() }
        return this
    }

    fun dismiss() = dialog.dismiss()

    @Suppress("DEPRECATION")
    fun show(): Sheet {
        dialog.show()
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.35f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Le panneau ne dépasse pas 85 % de l'écran ; il est décalé au-dessus de la barre de navigation.
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.85).toInt()
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (root.height > maxHeight) w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, maxHeight)
            }
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                else insets.systemWindowInsetBottom
                v.setPadding(0, 0, 0, bottom)
                insets
            }
            root.requestApplyInsets()
        }
        return this
    }
}
