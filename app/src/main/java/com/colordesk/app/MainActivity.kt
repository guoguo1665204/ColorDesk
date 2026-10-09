package com.colordesk.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import com.colordesk.app.core.PixelBox
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val ink=Color.rgb(30,38,57)
    private val muted=Color.rgb(102,111,133)
    private val accent=Color.rgb(83,105,232)
    private lateinit var content:LinearLayout
    private lateinit var status:TextView
    private var grid=GridConfig()
    private var kinds=mutableListOf<CellKind>()
    private var seen:DesktopCapture?=null
    private var plan:SortSession?=null
    private var paintKind=CellKind.ICON
    private var gridView:CalibrationView?=null
    private var summary:TextView?=null
    private var previewArea:LinearLayout?=null
    private var detectionText:TextView?=null

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val p=getPreferences(MODE_PRIVATE)
        grid=GridConfig(p.getInt("cols",4),p.getInt("rows",5),p.getFloat("top",.16f),
            p.getFloat("bottom",.80f),p.getFloat("side",.04f),p.getFloat("size",.55f),p.getFloat("center",.38f))
        render()
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);render() }
    override fun onResume() { super.onResume();if(DesktopService.capture!==seen) render() else if(::status.isInitialized) updateStatus() }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun text(value:String,size:Float=14f,color:Int=ink)=TextView(this).apply {
        this.text=value;textSize=size;setTextColor(color);setPadding(0,dp(4),0,dp(8))
    }
    private fun button(label:String,action:()->Unit)=Button(this).apply {
        this.text=label;isAllCaps=false;textSize=14f;setTextColor(accent);setOnClickListener{action()}
    }
    private fun card():LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14))
        background=GradientDrawable().apply { setColor(Color.WHITE);cornerRadius=dp(18).toFloat() }
        content.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })
    }
    private fun render() {
        val fresh=DesktopService.capture!==seen
        seen=DesktopService.capture
        if(kinds.size!=grid.count || seen==null) kinds=MutableList(grid.count){CellKind.FIXED}
        if(fresh && seen!=null) applyDetection(seen!!)
        plan=null;previewArea=null;summary=null;gridView=null
        val scroll=ScrollView(this).apply { isFillViewport=true;setBackgroundColor(Color.rgb(245,246,250)) }
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(24)) }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        setContentView(scroll)
        content.addView(text("COLOR DESK   /   0.2",12f,accent))
        content.addView(text("让桌面，按颜色排好。",26f))
        content.addView(text("保留原生桌面 · 本机处理 · Android 11+",13f,muted))
        val environment=card()
        environment.addView(text("01  连接原生桌面",17f))
        status=text("",13f,muted);environment.addView(status);updateStatus()
        environment.addView(button("开启无障碍服务") { explainPermission() })
        environment.addView(button("截取当前桌面页") {
            val service=DesktopService.instance
            if(service==null) explainPermission() else {
                kinds=MutableList(grid.count){CellKind.FIXED};plan=null
                service.captureHome()
            }
        })
        if(DesktopService.message.isNotBlank()) environment.addView(text(DesktopService.message,14f,accent))
        val capture=seen
        if(capture==null) {
            val intro=card()
            intro.addView(text("第一次使用",17f))
            intro.addView(text("1. 关闭桌面布局锁定，开启无障碍服务。\n2. 截取要整理的页面，自动识别图标位置。\n3. 检查识别框，确认至少一个真实空位。\n4. 查看颜色排序预览，先试运行一步。",14f,muted))
            intro.addView(text("本版支持单页规则网格。文件夹、小组件、底部常用栏保持固定；跨页整理尚未实现。所有桌面适配均待实机验证。",13f,muted))
            return
        }
        val automatic=card()
        automatic.addView(text("02  自动识别图标",17f))
        detectionText=text(capture.detection?.summary ?: "请重新截图以读取桌面图标位置",13f,muted).also { automatic.addView(it) }
        automatic.addView(text("蓝框为按应用名称匹配的位置，灰框为待确认位置。截图候选不会自动参与移动。可拖动图标框微调位置；调整下方手动网格会退出自动定位。",13f,muted))
        automatic.addView(button("重新应用自动识别结果") {
            if(applyDetection(capture)) render() else toast(capture.detection?.summary ?: "请重新截图")
        })
        automatic.addView(button("导出识别报告") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type="application/json";putExtra(Intent.EXTRA_TITLE,"ColorDesk-detection.json")
            },41)
        })
        val calibration=card()
        calibration.addView(text("手动校准（识别不准时使用）",17f))
        calibration.addView(text("调整到每个小方框正好包住图标，不包含文字。范围应避开底部常用栏。",13f,muted))
        val dimensions=LinearLayout(this)
        val columns=text(getString(R.string.grid_columns,grid.columns),14f)
        val rows=text(getString(R.string.grid_rows,grid.rows),14f)
        dimensions.addView(button("−") { if(grid.columns>2) { grid=grid.copy(columns=grid.columns-1);columns.text=getString(R.string.grid_columns,grid.columns);resetGrid() } },LinearLayout.LayoutParams(0,-2,1f))
        dimensions.addView(columns)
        dimensions.addView(button("+") { if(grid.columns<8) { grid=grid.copy(columns=grid.columns+1);columns.text=getString(R.string.grid_columns,grid.columns);resetGrid() } },LinearLayout.LayoutParams(0,-2,1f))
        dimensions.addView(button("−") { if(grid.rows>2) { grid=grid.copy(rows=grid.rows-1);rows.text=getString(R.string.grid_rows,grid.rows);resetGrid() } },LinearLayout.LayoutParams(0,-2,1f))
        dimensions.addView(rows)
        dimensions.addView(button("+") { if(grid.rows<10) { grid=grid.copy(rows=grid.rows+1);rows.text=getString(R.string.grid_rows,grid.rows);resetGrid() } },LinearLayout.LayoutParams(0,-2,1f))
        calibration.addView(dimensions)
        slider(calibration,"网格顶部",0,45,(grid.top*100).toInt()) { grid=grid.copy(top=it/100f);resetGrid() }
        slider(calibration,"网格底部",55,92,(grid.bottom*100).toInt()) { grid=grid.copy(bottom=it/100f);resetGrid() }
        slider(calibration,"左右边距",0,15,(grid.side*100).toInt()) { grid=grid.copy(side=it/100f);resetGrid() }
        slider(calibration,"图标框大小",25,80,(grid.iconRatio*100).toInt()) { grid=grid.copy(iconRatio=it/100f);resetGrid() }
        slider(calibration,"图标在格内的高度",20,65,(grid.centerY*100).toInt()) { grid=grid.copy(centerY=it/100f);resetGrid() }
        calibration.visibility=if(grid.automatic) View.GONE else View.VISIBLE
        automatic.addView(button("显示 / 隐藏手动校准") {
            calibration.visibility=if(calibration.visibility==View.GONE) View.VISIBLE else View.GONE
        })
        val selection=card()
        selection.addView(text("03  标记参与整理的位置",17f))
        selection.addView(text("检查识别框。选择「空位」后点按真实空位；自动模式下，网格外的空位也可直接点按指定。文件夹、小组件和其他位置保持固定。",13f,muted))
        val modes=RadioGroup(this).apply { orientation=RadioGroup.HORIZONTAL }
        listOf(CellKind.ICON to "图标",CellKind.EMPTY to "空位",CellKind.FIXED to "固定").forEach { (kind,label) ->
            val radio=RadioButton(this).apply { id=View.generateViewId();this.text=label;isChecked=paintKind==kind }
            modes.addView(radio)
            radio.setOnCheckedChangeListener { _,checked -> if(checked) paintKind=kind }
        }
        selection.addView(modes)
        gridView=CalibrationView(capture.bitmap).also { selection.addView(it) }
        summary=text("",13f,muted).also { selection.addView(it) };updateSummary()
        selection.addView(button("清除全部标记") { resetGrid(false) })
        selection.addView(button("生成颜色排序预览") { makePreview() })
        previewArea=card()
        previewArea?.addView(text("04  排序预览",17f))
        previewArea?.addView(text("完成标记后生成预览。空位最终保留在原位置。",13f,muted))
    }
    private fun applyDetection(capture:DesktopCapture):Boolean {
        val layout=capture.detection?.layout ?: return false
        grid=GridConfig(columns=layout.xs.size,rows=layout.ys.size,xs=layout.xs,ys=layout.ys,
            overrides=layout.icons.mapValues { it.value.box },autoSize=layout.iconSize)
        kinds=MutableList(grid.count) { index ->
            if(layout.icons[index]?.confirmedApp==true) CellKind.ICON else CellKind.FIXED
        }
        return true
    }
    @Deprecated("Framework document picker callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=41 || resultCode!=RESULT_OK) return
        val capture=seen ?: return
        val uri=data?.data ?: return
        val report=JSONObject().put("version","0.2.0").put("launcher",capture.launcher)
            .put("screenWidth",capture.bitmap.width).put("screenHeight",capture.bitmap.height)
            .put("summary",capture.detection?.summary).put("manufacturer",android.os.Build.MANUFACTURER)
            .put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE)
        report.put("nodes",JSONArray().apply {
            capture.nodes.forEach { n -> put(JSONObject().put("text",n.text).put("description",n.description)
                .put("id",n.viewId).put("class",n.className).put("ancestors",n.ancestors)
                .put("clickable",n.clickable).put("longClickable",n.longClickable)
                .put("bounds",JSONArray(listOf(n.bounds.left,n.bounds.top,n.bounds.right,n.bounds.bottom)))) }
        })
        report.put("icons",JSONArray().apply { capture.detection?.layout?.icons?.forEach { (index,icon) ->
            put(JSONObject().put("slot",index).put("name",icon.name).put("source",icon.source)
                .put("bounds",JSONArray(listOf(icon.box.left,icon.box.top,icon.box.right,icon.box.bottom))))
        } })
        try { contentResolver.openOutputStream(uri)?.use { it.write(report.toString(2).toByteArray(Charsets.UTF_8)) }
            ?: error("无法打开目标文件");toast("识别报告已保存，包含桌面应用名称和位置，不包含截图") }
        catch(_:Exception) { toast("保存失败，请重新选择位置") }
    }
    private fun updateStatus() {
        val home=packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
        val adapter=LauncherAdapters.forPackage(home.orEmpty())
        status.text=getString(R.string.environment_status,adapter.name,android.os.Build.VERSION.RELEASE,
            android.os.Build.MANUFACTURER,android.os.Build.MODEL,home ?: "默认桌面未识别",
            if(DesktopService.instance!=null) "已连接" else "未连接")
    }
    private fun explainPermission() {
        AlertDialog.Builder(this).setTitle("桌面整理需要无障碍权限")
            .setMessage("用于读取当前桌面、截取图标并模拟长按拖动。只有你点击开始后才执行整理；截图只在本机内存处理，不上传、不保存。\n\n进入系统设置后，找到「色序桌面助手」并开启服务。")
            .setNegativeButton("取消",null).setPositiveButton("前往设置") { _,_ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
    }
    private fun slider(parent:LinearLayout,label:String,min:Int,max:Int,value:Int,changed:(Int)->Unit) {
        val title=text(getString(R.string.slider_value,label,value),12f,muted);parent.addView(title)
        val seek=SeekBar(this).apply { this.max=max-min;progress=value-min }
        parent.addView(seek)
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {
                if(fromUser) { title.text=getString(R.string.slider_value,label,progress+min);changed(progress+min) }
            }
            override fun onStartTrackingTouch(seekBar:SeekBar?) {}
            override fun onStopTrackingTouch(seekBar:SeekBar?) {}
        })
    }
    private fun resetGrid(clearGeometry:Boolean=true) {
        if(clearGeometry) {
            grid=grid.copy(xs=emptyList(),ys=emptyList(),overrides=emptyMap(),extraSlots=emptyList())
            detectionText?.text="当前使用手动网格，可点击上方按钮恢复自动定位。"
        }
        kinds=MutableList(grid.count){CellKind.FIXED};invalidatePlan();gridView?.invalidate();updateSummary()
    }
    private fun invalidatePlan() {
        plan=null
        previewArea?.removeAllViews()
        previewArea?.addView(text("04  排序预览",17f))
        previewArea?.addView(text("布局或标记已改变，请重新生成预览。",13f,muted))
    }
    private fun updateSummary() {
        summary?.text=getString(R.string.cell_summary,kinds.count { it==CellKind.ICON },
            kinds.count { it==CellKind.EMPTY },kinds.count { it==CellKind.FIXED })
    }
    private fun makePreview() {
        val capture=seen ?: return
        try {
            val s=SortSession.create(capture,grid,kinds)
            plan=s
            getPreferences(MODE_PRIVATE).edit().putInt("cols",grid.columns).putInt("rows",grid.rows)
                .putFloat("top",grid.top).putFloat("bottom",grid.bottom).putFloat("side",grid.side)
                .putFloat("size",grid.iconRatio).putFloat("center",grid.centerY).apply()
            val area=previewArea ?: return
            area.removeAllViews();area.addView(text("04  排序预览",17f))
            area.addView(text("红 → 橙 → 黄 → 绿 → 蓝 → 紫 → 黑白灰\n${s.ranks.size} 个图标 · ${s.moves.size} 次移动",13f,muted))
            val tiles=GridLayout(this).apply { columnCount=grid.columns }
            for(i in kinds.indices) {
                val token=if(kinds[i]==CellKind.FIXED) i else s.target[i]
                val tile=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER }
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
                if(token!=null) image.setImageBitmap(VisualCheck.crop(capture.bitmap,grid,token))
                tile.addView(image,LinearLayout.LayoutParams(-1,dp(38)))
                tile.addView(text(when { kinds[i]==CellKind.FIXED -> "固定";token==null -> "空位";else -> s.ranks.getValue(token).label },10f,muted))
                tiles.addView(tile,GridLayout.LayoutParams().apply { width=0;height=dp(72);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f) })
            }
            area.addView(tiles)
            area.addView(text("多色图标按中央区域的主色归类，请检查预览。测试模式会先移动一步，核对通过后由你选择继续。整理期间不要触碰桌面。",13f,muted))
            area.addView(button(if(s.moves.isEmpty()) "已按颜色排列" else "进入桌面试运行") {
                if(s.moves.isEmpty()) return@button
                val service=DesktopService.instance
                if(service==null) { explainPermission();return@button }
                if(plan!==s || DesktopService.capture!==capture) { toast("截图已失效，请重新截图");return@button }
                val ambiguous=s.ranks.keys.toList().let { keys -> keys.indices.any { a -> (a+1 until keys.size).any { b ->
                    VisualCheck.distance(s.fingerprints.getValue(keys[a]),s.fingerprints.getValue(keys[b]))<22f
                } } }
                if(ambiguous) { toast("部分图标过于相似，无法可靠核对。请缩小选择范围或调整图标框。");return@button }
                AlertDialog.Builder(this).setTitle("开始单页试运行")
                    .setMessage("确认网格已对齐、空位确实为空，并已关闭桌面布局锁定。当前适配尚未经过实机验证，试运行会实际移动一个图标。\n\n请使用静态壁纸，停留在截图对应的页面。核对失败会停止，但不会自动撤销已经发生的移动。")
                    .setNegativeButton("返回检查",null).setPositiveButton("试运行") { _,_ -> service.arm(s) }.show()
            })
        } catch(e:IllegalArgumentException) { toast(e.message ?: "无法生成排序") }
    }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()

    inner class CalibrationView(private val bitmap:Bitmap):View(this@MainActivity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var scale=1f
        private var dx=0f
        private var dy=0f
        private var dragIndex:Int?=null
        private var downX=0f
        private var downY=0f
        private var dragged=false
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val width=MeasureSpec.getSize(widthMeasureSpec)
            val height=(width.toFloat()*bitmap.height/bitmap.width).toInt().coerceAtMost(dp(510))
            setMeasuredDimension(width,height)
        }
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            scale=minOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
            dx=(width-bitmap.width*scale)/2;dy=(height-bitmap.height*scale)/2
            canvas.save();canvas.translate(dx,dy);canvas.scale(scale,scale)
            paint.color=Color.WHITE;paint.style=Paint.Style.FILL
            canvas.drawBitmap(bitmap,0f,0f,paint)
            for(i in kinds.indices) {
                val color=when(kinds[i]) { CellKind.ICON->accent;CellKind.EMPTY->Color.rgb(32,171,131);CellKind.FIXED->Color.GRAY }
                val bounds=grid.slotBounds(i,bitmap.width,bitmap.height)
                val left=bounds.left.toFloat();val top=bounds.top.toFloat()
                paint.color=(color and 0xffffff) or 0x38000000;paint.style=Paint.Style.FILL
                canvas.drawRect(bounds,paint)
                paint.color=color;paint.strokeWidth=2f/scale;paint.style=Paint.Style.STROKE
                canvas.drawRect(grid.crop(i,bitmap.width,bitmap.height),paint)
                paint.style=Paint.Style.FILL;paint.textSize=12f/scale;paint.color=Color.WHITE
                val name=if(grid.automatic) seen?.detection?.layout?.icons?.get(i)?.name?.take(5).orEmpty() else ""
                canvas.drawText("${i+1} $name",left+5/scale,top+15/scale,paint)
            }
            canvas.restore()
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            val x=(event.x-dx)/scale;val y=(event.y-dy)/scale
            if(event.action==MotionEvent.ACTION_DOWN) {
                parent.requestDisallowInterceptTouchEvent(true)
                downX=x;downY=y;dragged=false
                dragIndex=if(grid.automatic) grid.slotAt(x,y,bitmap.width,bitmap.height)?.takeIf {
                    grid.crop(it,bitmap.width,bitmap.height).contains(x.toInt(),y.toInt()) } else null
                return true
            }
            if(event.action==MotionEvent.ACTION_MOVE && dragIndex!=null &&
                (dragged || kotlin.math.abs(x-downX)+kotlin.math.abs(y-downY)>dp(8)/scale)) {
                val index=dragIndex!!;val old=grid.crop(index,bitmap.width,bitmap.height)
                val box=PixelBox.square(x,y,old.width().toFloat(),bitmap.width,bitmap.height)
                grid=grid.copy(overrides=grid.overrides+(index to box));dragged=true;invalidatePlan();invalidate();return true
            }
            if(event.action==MotionEvent.ACTION_UP) {
                var index=grid.slotAt(x,y,bitmap.width,bitmap.height)
                if(!dragged && grid.automatic && paintKind==CellKind.EMPTY && index==null &&
                    x in bitmap.width*.03f..bitmap.width*.97f && y in bitmap.height*.05f..bitmap.height*.86f) {
                    val box=PixelBox.square(x,y,grid.autoSize*bitmap.width,bitmap.width,bitmap.height)
                    index=grid.count
                    grid=grid.copy(extraSlots=grid.extraSlots+box);kinds+=CellKind.FIXED
                }
                if(!dragged && index!=null) {
                    if(paintKind==CellKind.EMPTY && grid.automatic && seen?.detection?.layout?.icons?.get(index)?.confirmedApp==true) {
                        toast("这个位置识别到了应用，不能当作空位。请先腾出位置并重新截图。")
                    } else { kinds[index]=paintKind;invalidatePlan();updateSummary();invalidate();performClick() }
                }
                dragIndex=null;parent.requestDisallowInterceptTouchEvent(false);return true
            }
            if(event.action==MotionEvent.ACTION_CANCEL) parent.requestDisallowInterceptTouchEvent(false)
            return true
        }
        override fun performClick():Boolean { super.performClick();return true }
    }
}
