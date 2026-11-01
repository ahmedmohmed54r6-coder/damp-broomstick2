package com.securecall.app

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Noto Naskh Arabic, loaded from assets/fonts/naskh.ttf (the Gradle build downloads it; see README).
 *  If the file is missing the system serif is used (Naskh on most phones); nothing breaks. */
object Fonts {
    @Volatile private var tf: Typeface? = null
    @Volatile private var tried = false

    fun get(ctx: Context): Typeface? {
        if (!tried) {
            tried = true
            tf = try { Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/naskh.ttf") } catch (_: Exception) { Typeface.SERIF }
        }
        return tf
    }

    /** Applies the font to every TextView below [v] (monospace fields, e.g. TURN settings, are left alone). */
    fun apply(v: View?, ctx: Context) {
        val f = get(ctx) ?: return
        when (v) {
            is TextView -> if (v.typeface !== Typeface.MONOSPACE) v.setTypeface(f, v.typeface?.style ?: Typeface.NORMAL)
            is ViewGroup -> for (i in 0 until v.childCount) apply(v.getChildAt(i), ctx)
            else -> {}
        }
    }
}
