package com.german.portaljarviswake

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.*

/** A one-pixel, non-focusable/non-touchable overlay: no UI capture, only launch-policy compatibility. */
class LaunchOverlay(private val context: Context) {
    private var view: View? = null
    fun show() { if(view != null || !Settings.canDrawOverlays(context)) return; val v=View(context).apply { setBackgroundColor(Color.TRANSPARENT) }; val p=WindowManager.LayoutParams(1,1, if(android.os.Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT); (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v,p); view=v }
    fun hide() { view?.let { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }; view=null }
}
