package com.colordesk.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.colordesk.app.core.*
import java.util.concurrent.Executors

class DesktopService : AccessibilityService() {
    companion object {
        private var serviceRef=WeakReference<DesktopService>(null)
        val instance: DesktopService? get()=serviceRef.get()
        var capture: DesktopCapture? = null
        var message: String = ""
    }
    private val handler=Handler(Looper.getMainLooper())
    private var overlay: LinearLayout?=null
    private var active=false
    private var runId=0
    private var session: SortSession?=null
    private var current=mutableMapOf<Int,Int?>()
    private var step=0
    private var runAll=false
    private var moving=false
    private var strokeInFlight=false
    private val recognizer=Executors.newSingleThreadExecutor()

    override fun onServiceConnected() { serviceRef=WeakReference(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(active && event?.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg=event.packageName?.toString() ?: return
            val home=session?.capture?.launcher
            if(pkg!=home && pkg!=packageName && pkg!="com.android.systemui") {
                finish("桌面已切换，整理停止。请重新截取当前布局。")
            }
        }
    }
    override fun onInterrupt() { cancel() }
    override fun onDestroy() {
        active=false;runId++;handler.removeCallbacksAndMessages(null);hideOverlay()
        recognizer.shutdownNow()
        serviceRef.clear();capture=null
        super.onDestroy()
    }
    private fun rotation()=getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation
    private fun foregroundLauncher()=rootInActiveWindow?.packageName?.toString()
    fun captureHome() {
        if(active) return
        capture=null
        val generation=++runId
        val home=packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
        if(home==null || home=="android") { message="无法确定默认桌面，请先设置默认桌面";openApp();return }
        if(!performGlobalAction(GLOBAL_ACTION_HOME)) { message="无法返回桌面";openApp();return }
        handler.postDelayed({
            if(generation!=runId) return@postDelayed
            showOverlay("切换到要整理的桌面页，然后点击「截取此页」。", "截取此页", {
                hideOverlay()
                handler.postDelayed({
                    if(generation!=runId) return@postDelayed
                    if(foregroundLauncher()!=home) { message="未能读取原生桌面，请检查无障碍权限或关闭弹窗";openApp();return@postDelayed }
                    val orientation=rotation()
                    val nodes=try { readDesktopNodes(home) } catch(_:Exception) { emptyList() }
                    screenshot({bitmap ->
                        if(generation!=runId) return@screenshot
                        if(orientation!=rotation()) { message="截图时方向发生变化，请重试";openApp();return@screenshot }
                        message="正在自动识别桌面图标…"
                        recognizer.execute {
                            val result=try {
                                val labels=try { getSystemService(LauncherApps::class.java).getActivityList(null,android.os.Process.myUserHandle())
                                    .map { it.label.toString() }.toSet() } catch(_:Exception) { emptySet() }
                                val width=minOf(320,bitmap.width)
                                val thumb=Bitmap.createScaledBitmap(bitmap,width,(bitmap.height.toFloat()*width/bitmap.width).toInt(),true)
                                val pixels=IntArray(thumb.width*thumb.height)
                                thumb.getPixels(pixels,0,thumb.width,0,0,thumb.width,thumb.height)
                                val scale=bitmap.width.toFloat()/thumb.width
                                val visual=ImageIconDetection.detect(pixels,thumb.width,thumb.height).map {
                                    PixelBox((it.left*scale).toInt(),(it.top*scale).toInt(),(it.right*scale).toInt(),(it.bottom*scale).toInt())
                                }
                                LayoutDetection.detect(nodes,labels,visual,bitmap.width,bitmap.height)
                            } catch(_:Exception) { DetectionResult(null,"自动识别失败，可导出识别报告或手动校准。",nodes.size,0) }
                            handler.post {
                                if(generation==runId) { capture=DesktopCapture(bitmap,home,orientation,nodes,result);message=result.summary;openApp() }
                            }
                        }
                    }, { if(generation==runId) { message=it;openApp() } })
                },400)
            })
        },1500)
    }
    @Suppress("DEPRECATION")
    private fun readDesktopNodes(home:String):List<DesktopNode> {
        val out=mutableListOf<DesktopNode>()
        fun walk(node:AccessibilityNodeInfo,ancestors:String,depth:Int) {
            try {
                if(depth>32 || out.size>=2000) return
                val box=Rect();node.getBoundsInScreen(box)
                val id=node.viewIdResourceName.orEmpty();val cls=node.className?.toString().orEmpty()
                if(node.isVisibleToUser && node.packageName?.toString()==home) {
                    out+=DesktopNode(PixelBox(box.left,box.top,box.right,box.bottom),node.text?.toString().orEmpty(),
                        node.contentDescription?.toString().orEmpty(),id,cls,node.isClickable,node.isLongClickable,ancestors)
                }
                val lineage=(ancestors+" "+id+" "+cls).takeLast(800)
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it,lineage,depth+1) }
            } finally { node.recycle() }
        }
        rootInActiveWindow?.let { walk(it,"",0) }
        return out
    }
    private fun screenshot(success:(Bitmap)->Unit, failure:(String)->Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer=result.hardwareBuffer
                    try {
                        val hardware=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)
                        val bitmap=hardware?.copy(Bitmap.Config.ARGB_8888,false)
                        hardware?.recycle()
                        if(bitmap==null) failure("截图读取失败") else success(bitmap)
                    } finally { buffer.close() }
                }
                override fun onFailure(errorCode:Int) { failure("截图失败（$errorCode），请回到桌面后重试") }
            })
        } catch(e:Exception) { failure("截图不可用：${e.javaClass.simpleName}") }
    }
    fun arm(plan:SortSession) {
        if(active) return
        session=plan;current=plan.initial.toMutableMap();step=0;runAll=false;active=true;moving=false
        val generation=++runId
        if(!performGlobalAction(GLOBAL_ACTION_HOME)) { finish("返回桌面失败");return }
        handler.postDelayed({
            if(!active || generation!=runId) return@postDelayed
            if(!validDesktop()) { finish("桌面环境已改变，请重新校准");return@postDelayed }
            showOverlay("请切换到截图对应的桌面页。第一次只执行一个移动步骤。", "试运行一步", { next(false) })
        },1400)
    }
    private fun validDesktop():Boolean {
        val s=session ?: return false
        return foregroundLauncher()==s.capture.launcher && rotation()==s.capture.rotation
    }
    private fun verify(bitmap:Bitmap, state:Map<Int,Int?>):String? {
        val s=session ?: return "任务已失效"
        if(bitmap.width!=s.capture.bitmap.width || bitmap.height!=s.capture.bitmap.height) return "屏幕尺寸发生变化"
        // Check the whole grid, including fixed cells. Dynamic widgets can conservatively stop a run.
        for(i in s.kinds.indices) {
            val actual=VisualCheck.fingerprint(VisualCheck.crop(bitmap,s.grid,i))
            val expected=if(s.kinds[i]==CellKind.FIXED) s.fingerprints.getValue(i)
                else state[i]?.let { s.fingerprints.getValue(it) }
            if(expected!=null && VisualCheck.distance(actual,expected)>18f) return "第 ${i+1} 格图标与预期不符"
            if(expected==null) {
                // Previously known empty slots can be checked against their original wallpaper.
                if(s.initial[i]==null && VisualCheck.distance(actual,s.fingerprints.getValue(i))>18f) return "第 ${i+1} 格空位发生变化"
                // New empty slots must no longer contain any known movable icon.
                if(s.ranks.keys.any { VisualCheck.distance(actual,s.fingerprints.getValue(it))<12f }) return "第 ${i+1} 格没有腾空"
            }
        }
        return null
    }
    private fun next(all:Boolean) {
        if(!active || moving) return
        val s=session ?: return
        if(step>=s.moves.size) { finish("排序已完成，共执行 $step 次移动");return }
        moving=true;runAll=all;hideOverlay()
        val generation=runId
        handler.postDelayed({
            if(!active || generation!=runId) return@postDelayed
            if(!validDesktop()) { finish("请保持在原桌面和原方向");return@postDelayed }
            screenshot({ bitmap ->
                if(!active || generation!=runId) return@screenshot
                if(!validDesktop()) { finish("截图期间桌面或方向发生变化");return@screenshot }
                val error=verify(bitmap,current)
                if(error!=null) { finish("$error，已停止。请重新截图、检查网格。");return@screenshot }
                drag(generation)
            }, { if(active && generation==runId) finish(it) })
        },400)
    }
    private fun drag(generation:Int) {
        val s=session ?: return
        val move=s.moves[step]
        val a=s.grid.crop(move.from,s.capture.bitmap.width,s.capture.bitmap.height)
        val b=s.grid.crop(move.to,s.capture.bitmap.width,s.capture.bitmap.height)
        val adapter=LauncherAdapters.forPackage(s.capture.launcher)
        val holdPath=Path().apply { moveTo(a.exactCenterX(),a.exactCenterY()) }
        val stroke=GestureDescription.StrokeDescription(holdPath,0,adapter.holdMillis,true)
        if(!showMovingStop()) return
        try {
            strokeInFlight=true
            val accepted=dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),object:GestureResultCallback() {
                override fun onCompleted(gestureDescription:GestureDescription?) {
                    if(!active || generation!=runId) return
                    // Keep the same finger down between the long press and the drag.
                    val path=Path().apply { moveTo(a.exactCenterX(),a.exactCenterY());lineTo(b.exactCenterX(),b.exactCenterY()) }
                    val continuation=stroke.continueStroke(path,0,adapter.dragMillis,false)
                    val ok=dispatchGesture(GestureDescription.Builder().addStroke(continuation).build(),object:GestureResultCallback() {
                        override fun onCompleted(gestureDescription:GestureDescription?) {
                            strokeInFlight=false
                            if(!active || generation!=runId) return
                            hideOverlay()
                            handler.postDelayed({ checkAfterMove(generation) },adapter.settleMillis)
                        }
                        override fun onCancelled(gestureDescription:GestureDescription?) { strokeInFlight=false;if(active && generation==runId) finish("拖动被取消，请重新截图") }
                    },handler)
                    if(!ok) finish("桌面拒绝拖动手势")
                }
                override fun onCancelled(gestureDescription:GestureDescription?) { strokeInFlight=false;if(active && generation==runId) finish("长按被取消，请重新截图") }
            },handler)
            if(!accepted) finish("系统拒绝模拟手势，请检查权限")
        } catch(e:Exception) { finish("拖动失败：${e.javaClass.simpleName}") }
    }
    private fun showMovingStop():Boolean {
        hideOverlay()
        val layout=LinearLayout(this)
        layout.addView(Button(this).apply { text="停止整理";setOnClickListener { cancel() } })
        val p=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.END }
        return try { getSystemService(WindowManager::class.java).addView(layout,p);overlay=layout;true }
        catch(_:Exception) { finish("无法显示停止按钮，任务未执行");false }
    }
    private fun releaseTouch() {
        if(!strokeInFlight) return
        strokeInFlight=false
        // A replacement stationary gesture cancels any continued stroke.
        try { dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(Path().apply { moveTo(1f,1f) },0,1)).build(),null,null) }
        catch(_:Exception) {}
    }
    private fun checkAfterMove(generation:Int) {
        if(!active || generation!=runId) return
        if(!validDesktop()) { finish("桌面状态改变，已停止");return }
        val s=session ?: return
        val move=s.moves[step]
        val expected=current.toMutableMap().apply { this[move.to]=move.token;this[move.from]=null }
        screenshot({ bitmap ->
            if(!active || generation!=runId) return@screenshot
            if(!validDesktop()) { finish("核对期间桌面或方向发生变化");return@screenshot }
            val error=verify(bitmap,expected)
            if(error!=null) { finish("移动后核对失败：$error。已停止，请检查桌面。");return@screenshot }
            current=expected;step++;moving=false
            if(step==s.moves.size) { finish("排序已完成，共执行 $step 次移动");return@screenshot }
            if(runAll) next(true)
            else showOverlay("第 $step 步核对通过。剩余 ${s.moves.size-step} 步。", "继续一步", { next(false) }, "执行剩余", { next(true) })
        }, { if(active && generation==runId) finish(it) })
    }
    private fun showOverlay(text:String,label:String?,action:(()->Unit)?,secondLabel:String?=null,second:(()->Unit)?=null) {
        hideOverlay()
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(18,12,18,12);setBackgroundColor(Color.rgb(30,38,57)) }
        layout.addView(TextView(this).apply { this.text=text;textSize=12f;setTextColor(Color.WHITE) })
        val buttons=LinearLayout(this)
        if(label!=null) buttons.addView(Button(this).apply { this.text=label;setOnClickListener { action?.invoke() } })
        if(secondLabel!=null) buttons.addView(Button(this).apply { this.text=secondLabel;setOnClickListener { second?.invoke() } })
        buttons.addView(Button(this).apply { this.text="停止";setOnClickListener { cancel() } })
        layout.addView(buttons)
        val p=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        try { getSystemService(WindowManager::class.java).addView(layout,p);overlay=layout }
        catch(e:Exception) { active=false;moving=false;runId++;message="无法显示停止按钮，任务未执行";openApp() }
    }
    private fun hideOverlay() {
        overlay?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch(_:Exception){} }
        overlay=null
    }
    fun cancel() {
        active=false;moving=false;runId++;handler.removeCallbacksAndMessages(null);hideOverlay();capture=null
        releaseTouch()
        session=null;message="已停止。已完成的移动保留，请重新截图确认布局。";openApp()
    }
    private fun finish(text:String) {
        active=false;moving=false;runId++;releaseTouch();hideOverlay();session=null;capture=null;message=text;openApp()
    }
    private fun openApp() {
        try { startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        catch(_:Exception) { /* Result remains available when the user reopens the app. */ }
    }
}
