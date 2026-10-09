package com.colordesk.app.core

import kotlin.math.*

data class PixelBox(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    val width get()=right-left
    val height get()=bottom-top
    val x get()=(left+right)/2f
    val y get()=(top+bottom)/2f
    fun contains(x:Float,y:Float)=x>=left && x<right && y>=top && y<bottom
    fun clipped(w:Int,h:Int)=PixelBox(left.coerceIn(0,w-1),top.coerceIn(0,h-1),right.coerceIn(1,w),bottom.coerceIn(1,h))
    companion object {
        fun square(x:Float,y:Float,size:Float,w:Int,h:Int)=PixelBox((x-size/2).roundToInt(),(y-size/2).roundToInt(),
            (x+size/2).roundToInt(),(y+size/2).roundToInt()).clipped(w,h)
    }
}
data class DesktopNode(val bounds:PixelBox,val text:String,val description:String,val viewId:String,
    val className:String,val clickable:Boolean,val longClickable:Boolean,val ancestors:String)
data class LocatedIcon(val name:String,val box:PixelBox,val source:String,val confirmedApp:Boolean)
data class DetectedLayout(val xs:List<Float>,val ys:List<Float>,val icons:Map<Int,LocatedIcon>,val iconSize:Float)
data class DetectionResult(val layout:DetectedLayout?,val summary:String,val nodeCount:Int,val visualCount:Int)

object LayoutDetection {
    private fun normalized(s:String)=s.trim().replace("\u200b","").lowercase()
    private fun blocked(n:DesktopNode):Boolean {
        val hints=(n.viewId+" "+n.className+" "+n.ancestors).lowercase().replace("android.widget.","")
        return listOf("folder","widget","hotseat","dock","search_bar","searchbar","taskbar","all_apps","allapps","app_drawer").any { it in hints } ||
            Regex("文件夹|小组件|文件夾|folder",RegexOption.IGNORE_CASE).containsMatchIn(n.description)
    }
    fun detect(nodes:List<DesktopNode>,appLabels:Set<String>,visual:List<PixelBox>,width:Int,height:Int):DetectionResult {
        require(width>0 && height>0)
        val labels=appLabels.map(::normalized).filter { it.isNotEmpty() }.sortedByDescending { it.length }
        val nativeImages=nodes.filter { n ->
            val b=n.bounds
            !blocked(n) && n.className.endsWith("ImageView") && b.width in (width*.065f).toInt()..(width*.20f).toInt() &&
                b.height in (b.width*.8f).toInt()..(b.width*1.2f).toInt() && b.left>=0 && b.top>=0 && b.right<=width && b.bottom<=height
        }.map { it.bounds }
        val icons=mutableListOf<LocatedIcon>()
        for(n in nodes) {
            val b=n.bounds
            if(blocked(n) || b.width<=0 || b.height<=0 || b.left<0 || b.top<0 || b.right>width || b.bottom>height ||
                b.x<width*.025f || b.x>width*.975f || b.y<height*.045f || b.y>height*.86f ||
                b.width>width*.38f || b.height>height*.20f) continue
            val texts=listOf(normalized(n.text),normalized(n.description))
            val label=labels.firstOrNull { known -> texts.any { text -> text==known ||
                (text.startsWith(known) && text.length>known.length && text[known.length] in " ,，、;；(（:\n") } }
            if(label==null) continue
            val labelOnly=b.height<height*.035f
            val size=if(labelOnly) width*.115f else min(b.width*.72f,b.height*.64f).coerceIn(width*.065f,width*.18f)
            val expectedY=if(labelOnly) b.top-size*.62f else b.top+min(b.height*.40f,size*.65f)
            fun nearby(candidates:List<PixelBox>)=candidates.filter { candidate ->
                abs(candidate.x-b.x)<max(width*.035f,b.width*.45f) &&
                if(labelOnly) candidate.bottom<=b.top+height*.006f && b.top-candidate.bottom<height*.065f
                else candidate.y>=b.top && candidate.y<=b.bottom && candidate.left>=b.left-width*.02f && candidate.right<=b.right+width*.02f
            }.minByOrNull { abs(it.x-b.x)+abs(it.y-expectedY) }
            val native=nearby(nativeImages)
            val image=nearby(visual)
            val box=native ?: image ?: PixelBox.square(b.x,expectedY,size,width,height)
            icons+=LocatedIcon(if(n.text.isNotBlank()) n.text.trim() else label,box,
                when { native!=null -> "桌面图像节点";image!=null -> "桌面节点 + 截图边缘";else -> "桌面节点估算" },true)
        }
        val semantic=icons.sortedByDescending { when(it.source) { "桌面图像节点" -> 2;"桌面节点 + 截图边缘" -> 1;else -> 0 } }
            .fold(mutableListOf<LocatedIcon>()) { found,icon ->
                if(found.none { abs(it.box.x-icon.box.x)<width*.04f && abs(it.box.y-icon.box.y)<height*.035f }) found+=icon
                found
            }
        val fallback=(nativeImages+visual).fold(mutableListOf<PixelBox>()) { found,box ->
            if(found.none { abs(it.x-box.x)<width*.04f && abs(it.y-box.y)<height*.035f }) found+=box
            found
        }
        val candidates=if(semantic.size>=2) semantic else fallback.filter { it.y in height*.05f..height*.85f }.map {
            LocatedIcon("候选图标",it,"截图候选，需确认",false)
        }
        val layout=infer(candidates,width,height)
        val summary=when {
            layout==null -> "未能可靠推断布局：读取 ${nodes.size} 个桌面节点、${visual.size} 个截图候选。可导出识别报告或使用手动校准。"
            semantic.size>=2 -> "识别 ${layout.icons.size} 个应用位置，推断 ${layout.xs.size} 列 × ${layout.ys.size} 行。请检查框选；空位需手动确认。"
            else -> "找到 ${layout.icons.size} 个图像候选，尚不能确认它们都是应用。请检查并标记，空位需手动确认。"
        }
        return DetectionResult(layout,summary,nodes.size,visual.size)
    }
    fun infer(icons:List<LocatedIcon>,width:Int,height:Int):DetectedLayout? {
        if(icons.size<2) return null
        val sizes=icons.map { min(it.box.width,it.box.height).toFloat() }.sorted()
        val size=sizes[sizes.size/2]
        val usable=icons.filter { min(it.box.width,it.box.height) in (size*.6f).toInt()..(size*1.6f).toInt() }
        fun cluster(values:List<Float>,tolerance:Float):List<Float> {
            val groups=mutableListOf<MutableList<Float>>()
            for(v in values.sorted()) {
                if(groups.isEmpty() || abs(groups.last().average()-v)>tolerance) groups+=mutableListOf(v)
                else groups.last()+=v
            }
            return groups.map { it.average().toFloat() }
        }
        fun fillHoles(centers:List<Float>):List<Float> {
            if(centers.size<3) return centers
            val gaps=centers.zipWithNext { a,b -> b-a }.sorted()
            val step=gaps.first()
            if(step<=size*.8f) return centers
            val out=mutableListOf(centers.first())
            for((a,b) in centers.zipWithNext()) {
                val multiple=((b-a)/step).roundToInt()
                if(multiple in 2..3 && abs((b-a)/step-multiple)<.16f) {
                    for(k in 1 until multiple) out+=a+(b-a)*k/multiple
                }
                out+=b
            }
            return out
        }
        val xs=fillHoles(cluster(usable.map { it.box.x },max(size*.35f,width*.025f)))
        val ys=fillHoles(cluster(usable.map { it.box.y },max(size*.40f,height*.015f)))
        if(xs.size !in 1..8 || ys.size !in 1..10 || xs.size*ys.size<usable.size) return null
        val slots=mutableMapOf<Int,LocatedIcon>()
        for(icon in usable) {
            val col=xs.indices.minBy { abs(xs[it]-icon.box.x) }
            val row=ys.indices.minBy { abs(ys[it]-icon.box.y) }
            val index=row*xs.size+col
            if(index in slots) return null // Irregular overlapping candidates are not an automatic grid.
            slots[index]=icon
        }
        if(slots.size<2) return null
        return DetectedLayout(xs.map { it/width },ys.map { it/height },slots,size/width)
    }
}
