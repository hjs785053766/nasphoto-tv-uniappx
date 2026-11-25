package uts.sdk.modules.fnosAuth

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.Window
import io.dcloud.uts.UTSAndroid
import java.lang.ref.WeakReference

/**
 * 电视遥控器方向键 Hook。
 *
 * uni-app x 的 uvue 在 Android 上没有原生的「焦点树」，D-pad 不会自动在元素间移动，
 * 所以这里在 Activity 的 Window.Callback 上包一层，把按键事件回传给 UTS 页面，
 * 由页面自己维护焦点索引。
 *
 * 关键点（改之前先看完，都是真机踩过的）：
 *   - 用 Kotlin 的接口委托 `by base` 包一层，只重写 dispatchKeyEvent，
 *     其余（输入法、音量等）全部原样转交给系统，避免影响输入框；
 *   - **返回键是个例外**：只要当前有页面订阅，就由 App 独占（`return true`），
 *     不再交给系统。否则同一次返回会被处理两遍（App 关弹窗/关查看器 + 系统弹页面），
 *     真机现象见 5.22；没有订阅时（导航过渡期）仍然交给系统；
 *   - 只上报 ACTION_DOWN，避免按下/抬起各触发一次；
 *   - 回调在 UI 线程执行，UTS 侧可直接改响应式状态；
 *   - **包装器装上去就不再摘**：uni.reLaunch 时新旧页面的 onLoad / onUnload 顺序不保证，
 *     旧页面的 onUnload 里 stop() 若是「还原 window.callback」，就会把新页面刚装好的
 *     hook 一起摘掉，表现为「登录页遥控器正常，进图库后所有按键失效」。
 *     现在 stop() 只清订阅回调，包装器一直留着（对系统行为零影响，因为它始终转发给 base）；
 *   - **订阅者带 id**：只有当前持有者能调 stop()，过期页面的 stop 直接忽略；
 *   - 若 Activity 被重建 / window.callback 被运行时换掉，下一次 start() 会检测到并重新包装；
 *   - **静态成员一律用弱引用**（2026-11-15 LeakCanary 实测）：包装器通过 `by base`
 *     强持 window.callback → 旧 Activity，写成强引用会让销毁的设置页 / 图库页整页泄漏
 *     （`FnOsTvKeys.wrapper` → `AppCompatWindowCallback` → `UniLandscapePageActivity`）。
 *     换成 WeakReference 后静态侧不再钉住 Activity，包装器随 Window 一起回收；
 *   - **绝对不要用 `Application.ActivityLifecycleCallbacks` 做清理**（2026-11-15 真机崩过）：
 *     该接口在 Android 12+ 有 default 方法（`onActivityPrePaused` / `onActivityPreResumed`…），
 *     编译器会为匿名实现生成调用 `Application$ActivityLifecycleCallbacks$-CC` 的桥接，
 *     而 UTS 插件的 dex **没有做 interface default method 的 desugar**，
 *     运行时直接 `NoClassDefFoundError: android.app.Application$ActivityLifecycleCallbacks$-CC`。
 */
object FnOsTvKeys {

    private const val TAG = "FnOsTvKeys"

    /** 装 hook 的那个 Activity（弱引用：不能挡住 Activity 回收） */
    private var activityRef: WeakReference<Activity>? = null

    /** 我们装到 window.callback 上的包装器（弱引用：Activity 销毁后随 window 一起回收） */
    private var wrapperRef: WeakReference<Window.Callback>? = null

    private var callback: ((Number) -> Unit)? = null

    /** 当前有效订阅者 id（-1 = 没有）；过期页面的 stop 靠它忽略 */
    private var currentId: Long = -1L

    /** 主线程 Handler：安装自愈用 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 自愈任务（只在有订阅期间运行） */
    private var watchdog: Runnable? = null

    /** 上一颗「交给页面」的键码与时间：用于过滤系统 D-pad 连发（见 dispatchKeyEvent） */
    private var lastDownCode = -1
    private var lastDownAt = 0L

    /**
     * 订阅按键。id 由 UTS 侧的自增计数器生成（**不要改回 Date.now()**：同毫秒会撞车，
     * 导致旧页面的 stop 把新页面的通道清掉，见 AGENTS 5.29）。
     * 返回 false 表示当前环境装不上（非 App / 还没有窗口）—— 交给 watchdog 重试。
     */
    fun start(id: Number, cb: (Number) -> Unit): Boolean {
        currentId = id.toLong()
        callback = cb
        val ok = ensureInstalled()
        ensureWatchdog()
        return ok
    }

    /**
     * 退订：只有当前持有者能退，避免上一页的 onUnload 把下一页的通道关掉。
     *
     * **不销毁 watchdog**：以前这里把轮询停掉，一旦「某页 stop 了、下一页还没 arm」的空档
     * 出现异常，整个应用就永久收不到按键（真机表现为「登录页遥控器彻底失效」）。
     * 轮询本身在 `callback == null` 时什么都不做，留住它才能自愈。
     */
    fun stop(id: Number) {
        if (id.toLong() != currentId) {
            return
        }
        currentId = -1L
        callback = null
    }

    /**
     * 由 App 独占的按键：方向键 / OK / 回车 / 返回 / 菜单。
     *
     * 这些键**不能**再转给系统：转给系统后，可滚动的 `scroll-view` 会被当成原生可滚动控件
     * 再滚一次（真机现象：时间轴按一下下键跳两年、首页卡片条整条平滑、网格被带偏）。
     * 音量键、媒体键、输入法等其它键不在表里，照常交给系统。
     */
    private fun isOwnedKey(code: Int): Boolean {
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU -> true
            else -> false
        }
    }

    /**
     * 安装自愈（**真机踩到的坑，别删**）。
     *
     * 2026-11-23 实测：**冷启动后遥控器完全没反应，按一下遥控器「主页」回到桌面、再切回 App 就好了**；
     * 之后再失效，同样操作又能恢复。
     *
     * 原因：冷启动时 `window.callback` 往往在页面 onLoad **之后**才被框架 / AppCompat 换成它自己的实现，
     * 我们早先包上去的 wrapper 直接被顶掉 → `dispatchKeyEvent` 再也到不了这边；
     * 而「回桌面再回来」会触发页面 onShow → 再调一次 start() → 检测到 base 变了重新包装，于是就好了。
     *
     * 现在只要有订阅，就每 2 秒做一次 `ensureInstalled()`。已经装好时它只比较一次引用就 return，
     * 开销可以忽略；但能在框架任何时候换掉 callback 后很快自愈，用户不必再去按主页键。
     */
    private fun ensureWatchdog() {
        if (watchdog != null) {
            return
        }
        val task = object : Runnable {
            override fun run() {
                // 有订阅才真正检查；没订阅也继续排队（一次引用比较的开销，可以忽略），
                // 这样任何一次「stop 之后长时间没人 arm」都能在下一页 arm 时立刻接上
                // 后台时 getUniActivity() 可能为 null，先探一下，免得每 800ms 刷一条
                // "install failed: no uni activity" 的日志
                if (callback != null && UTSAndroid.getUniActivity() != null) {
                    ensureInstalled()
                }
                mainHandler.postDelayed(this, 800L)
            }
        }
        watchdog = task
        // 第一次只等 200ms、之后每 800ms 一次：
        // 真机实测（19:36 冷启动 / 19:38 进图库）框架会在**首帧之后**以及**每次页面切换**时
        // 把 window.callback 换成自己的实现，我们包的 wrapper 随之失效；
        // 这个轮询保证失效后 1 秒内自动重新包上，用户不必再去按遥控器的主页键。
        mainHandler.postDelayed(task, 200L)
    }

    /**
     * 装 hook 前先把「上一任 Activity 已经销毁」这件事收干净。
     * 只清引用，不动 `callback` / `currentId`：start() 刚把它们换成新页面的值了，
     * 而且旧页面的闭包此时已经被覆盖，不存在残留。
     */
    private fun dropDeadActivity() {
        val prev = activityRef?.get()
        if (prev == null || prev.isDestroyed) {
            activityRef = null
            wrapperRef = null
        }
    }

    private fun ensureInstalled(): Boolean {
        val activity = UTSAndroid.getUniActivity()
        if (activity == null) {
            Log.w(TAG, "install failed: no uni activity")
            return false
        }
        val window = activity.window
        if (window == null) {
            Log.w(TAG, "install failed: no window")
            return false
        }
        val base = window.callback
        if (base == null) {
            Log.w(TAG, "install failed: no window callback")
            return false
        }
        dropDeadActivity()
        // 已经挂在这个 **Activity 的窗口** 上、且回调还是我们的包装器 → 收工。
        //
        // ⚠️ 必须同时比较 Activity（2026-11-24 真机踩到）：
        // uni-app x 换页（尤其 reLaunch 到登录页）会新建一个 UniLandscapePageActivity，
        // 新窗口的 callback 有可能**恰好还是我们那个包装器对象**（旧的），
        // 只看 `base === current` 就误判「已经装好了」，于是新页面的按键永远进不来
        // （真机现象：reLaunch 到登录页后遥控器完全没反应，按键诊断里一条 key= 都没有）。
        val current = wrapperRef?.get()
        val prevActivity = activityRef?.get()
        if (current != null && base === current && prevActivity != null && prevActivity === activity) {
            return true
        }
        val w = object : Window.Callback by base {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val code = event.keyCode
                val cb = callback
                // 只要有页面订阅，**方向键 / OK / 返回 / 菜单全部由 App 独占**（DOWN 和 UP 都吃掉）。
                //
                // 为什么必须吃掉（2026-11-23 真机实测）：
                // 以前只吃 BACK、其余 `return base.dispatchKeyEvent(event)`，于是同一次方向键会被处理两遍——
                // 我们改自己的光标，系统同时把「可滚动的 scroll-view」当原生控件滚一页：
                // 图库时间轴按一下下键直接跳了两年、首页整条卡片条平滑滚动、网格被系统滚动带偏，
                // 全是这么来的。TV 应用必须独占 D-pad。
                //
                // 不会影响输入法：软键盘是独立窗口，按键不会进到我们这层回调。
                if (cb != null && isOwnedKey(code)) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val now = SystemClock.uptimeMillis()
                        // 同一颗键 100ms 内的重复按下：系统 D-pad 连发（长按）就是这个节奏（约 50ms 一次），
                        // 不做过滤时用户轻轻一按若稍微久一点，就会连动两格（真机反馈「左右有时跳一张」）。
                        // 按住不动仍然能连续移动（约 10 次/秒），手感与以前一致。
                        if (code == lastDownCode && now - lastDownAt < 100L) {
                            return true
                        }
                        lastDownCode = code
                        lastDownAt = now
                        Log.d(TAG, "key=$currentId/$code hasCallback=true")
                        runCatching { cb.invoke(code) }
                    }
                    return true
                }
                // 其它键（音量 / 媒体键 / 输入法等）照常交给系统
                return base.dispatchKeyEvent(event)
            }
        }
        wrapperRef = WeakReference(w)
        activityRef = WeakReference(activity)
        window.callback = w
        Log.i(TAG, "key hook installed for id=$currentId")
        return true
    }
}
