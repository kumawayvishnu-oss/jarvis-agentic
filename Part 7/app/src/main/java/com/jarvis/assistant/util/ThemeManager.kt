package com.jarvis.assistant.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import androidx.core.view.WindowInsetsControllerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

object ThemeManager {

    fun resolveThemeColor(context: Context, attrRes: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }

    fun isLight(colorInt: Int): Boolean {
        val r = Color.red(colorInt) / 255.0
        val g = Color.green(colorInt) / 255.0
        val b = Color.blue(colorInt) / 255.0
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum > 0.55
    }

    fun getThemeBgColor(themeKey: String, customHex: String?): Int {
        return when (themeKey) {
            "dark" -> Color.parseColor("#05080F")
            "red" -> Color.parseColor("#0E0307")
            "sunset" -> Color.parseColor("#FFF0EA")
            "custom" -> try { Color.parseColor(customHex ?: "#05080F") } catch (e: Exception) { Color.parseColor("#05080F") }
            else -> Color.parseColor("#05080F")
        }
    }

    fun applyTheme(activity: Activity) {
        val prefs = try { JarvisApp.instance.preferences } catch (e: Exception) { null }
        val themeKey = prefs?.appTheme ?: "dark"

        val (themeRes, isLightStatusBar, bgColor) = when (themeKey) {
            "dark" -> Triple(R.style.Theme_JarvisAssistant_Dark, false, Color.parseColor("#05080F"))
            "red" -> Triple(R.style.Theme_JarvisAssistant_Red, false, Color.parseColor("#0E0307"))
            "sunset" -> Triple(R.style.Theme_JarvisAssistant_Sunset, true, Color.parseColor("#FFF0EA"))
            "custom" -> {
                val customHex = prefs?.customThemeColor ?: "#05080F"
                val parsedColor = try { Color.parseColor(customHex) } catch (e: Exception) { Color.parseColor("#05080F") }
                val light = isLight(parsedColor)
                val res = if (light) R.style.Theme_JarvisAssistant_CustomLight else R.style.Theme_JarvisAssistant_CustomDark
                Triple(res, light, parsedColor)
            }
            else -> Triple(R.style.Theme_JarvisAssistant_Dark, false, Color.parseColor("#05080F"))
        }

        activity.setTheme(themeRes)
        activity.window.setBackgroundDrawable(ColorDrawable(bgColor))
        activity.window.decorView.setBackgroundColor(bgColor)
        activity.window.statusBarColor = bgColor
        activity.window.navigationBarColor = bgColor

        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = isLightStatusBar
        controller.isAppearanceLightNavigationBars = isLightStatusBar

        try {
            activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(bgColor)
        } catch (e: Exception) {}
        activity.window.decorView.post {
            try {
                activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(bgColor)
            } catch (e: Exception) {}
        }
    }

    fun applyDynamicBackground(view: View) {
        val prefs = try { JarvisApp.instance.preferences } catch (e: Exception) { null }
        val themeKey = prefs?.appTheme ?: "dark"
        val customHex = prefs?.customThemeColor ?: "#05080F"
        val color = getThemeBgColor(themeKey, customHex)
        view.setBackgroundColor(color)
        applyBundledTypographyAndMotion(view)
    }

    /**
     * Applies bundled fonts (@font/orbitron_bold & @font/rajdhani_semibold) and smooth fluid entry animation.
     */
    fun applyBundledTypographyAndMotion(root: View) {
        try {
            val ctx = root.context
            val orbitron = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.orbitron_bold)
            val rajdhaniSemi = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.rajdhani_semibold)
            val rajdhaniReg = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.rajdhani_regular)

            fun styleHierarchy(v: View) {
                if (v is android.widget.TextView) {
                    val spSize = v.textSize / ctx.resources.displayMetrics.scaledDensity
                    when {
                        spSize >= 18f && orbitron != null -> v.typeface = orbitron
                        v.typeface?.isBold == true && rajdhaniSemi != null -> v.typeface = rajdhaniSemi
                        rajdhaniReg != null -> v.typeface = rajdhaniReg
                    }
                } else if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) {
                        styleHierarchy(v.getChildAt(i))
                    }
                }
            }
            styleHierarchy(root)

            // Smooth 60fps hardware-accelerated fluid entrance transition
            root.alpha = 0.92f
            root.animate()
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()
        } catch (_: Exception) {
        }
    }
}
