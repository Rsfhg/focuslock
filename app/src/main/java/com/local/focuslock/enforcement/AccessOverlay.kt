package com.local.focuslock.enforcement

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputFilter
import android.text.InputType
import android.view.*
import android.widget.*
import com.local.focuslock.domain.*
import com.local.focuslock.ui.formatTime

class AccessOverlay(private val service: AccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var key: String? = null
    private var remaining: TextView? = null
    val ownership = GateOwnership()
    val isShowing: Boolean get() = root != null
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    fun show(pkg: String, label: String, status: Status, limit: Int, request: (Int, (String?) -> Unit) -> Unit, exit: () -> Unit) {
        val next = "$pkg:${status.phase}"
        val hint = if (status.phase == Phase.COOLING) "冷却剩余 ${formatTime(status.remainingMs)}" else "单次最多 $limit 分钟 · 开始后不能加时"
        if (key == next && root != null) { remaining?.text = hint; return }
        dismiss()
        val frame = FrameLayout(service).apply { setBackgroundColor(Color.rgb(245, 248, 245)); isClickable = true }
        val column = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(48), dp(28), dp(28)) }
        frame.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        fun text(value: String, size: Float): TextView = TextView(service).apply { text = value; textSize = size; setTextColor(Color.rgb(30, 55, 45)); setPadding(0, dp(10), 0, dp(10)); column.addView(this) }
        text("限时用", 16f)
        text(label, 30f)
        text(if (status.phase == Phase.COOLING) "休息一下，再回来" else "这次打算用多久？", 22f)
        remaining = text(hint, 16f)
        if (status.phase == Phase.READY) {
            val input = EditText(service).apply { inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(4)); setSingleLine(); this.hint = "输入整数分钟"; textSize = 24f }
            column.addView(input, LinearLayout.LayoutParams(-1, dp(64)))
            val button = Button(service).apply { text = "开始使用" }
            column.addView(button, LinearLayout.LayoutParams(-1, dp(60)))
            val error = text("", 14f)
            button.setOnClickListener {
                val minutes = DurationInput.parse(input.text.toString(), limit)
                if (minutes == null) error.text = "请输入 1–$limit 的整数分钟"
                else {
                    button.isEnabled = false
                    request(minutes) { failure -> if (failure != null) { error.text = failure; button.isEnabled = true } }
                }
            }
        }
        column.addView(Button(service).apply { text = "返回桌面"; setOnClickListener { exit() } }, LinearLayout.LayoutParams(-1, dp(60)))
        text("切换应用或锁屏不暂停倒计时。\n到期仅返回桌面，后台音频或下载可能继续。", 13f)
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.OPAQUE)
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        frame.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                frame.viewTreeObserver.removeOnPreDrawListener(this)
                GuardDiagnostics.record("gate first draw $pkg ${status.phase}")
                return true
            }
        })
        manager.addView(frame, params)
        root = frame; key = next
    }
    fun dismiss() {
        ownership.replace()
        root?.let { try { manager.removeViewImmediate(it) } catch (_: IllegalArgumentException) { } }
        root = null; key = null; remaining = null
    }
}
