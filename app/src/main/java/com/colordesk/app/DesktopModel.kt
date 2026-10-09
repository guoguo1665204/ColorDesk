package com.colordesk.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.colordesk.app.core.ColorRank
import com.colordesk.app.core.Move
import com.colordesk.app.core.MovePlanner
import com.colordesk.app.core.*
import kotlin.math.abs
import kotlin.math.min

enum class CellKind { FIXED, ICON, EMPTY }

data class GridConfig(
    val columns: Int = 4, val rows: Int = 5,
    val top: Float = .16f, val bottom: Float = .80f,
    val side: Float = .04f, val iconRatio: Float = .55f,
    val centerY: Float = .38f,
    val xs: List<Float> = emptyList(), val ys: List<Float> = emptyList(),
    val overrides: Map<Int,PixelBox> = emptyMap(), val autoSize: Float = .115f,
    val extraSlots:List<PixelBox> = emptyList()
) {
    val count get() = columns * rows + extraSlots.size
    val automatic get()=xs.size==columns && ys.size==rows
    fun slotBounds(index:Int,width:Int,height:Int):Rect {
        if(index>=columns*rows) {
            val b=extraSlots[index-columns*rows].clipped(width,height)
            return Rect(b.left,b.top,b.right,b.bottom)
        }
        fun edge(centers:List<Float>,i:Int,end:Boolean):Float {
            val c=centers[i]
            return if(end) { if(i<centers.lastIndex) (c+centers[i+1])/2 else c+(if(i>0) c-centers[i-1] else autoSize)/2 }
                else { if(i>0) (c+centers[i-1])/2 else c-(if(centers.size>1) centers[1]-c else autoSize)/2 }
        }
        if(automatic) {
            val col=index%columns;val row=index/columns
            return Rect((edge(xs,col,false)*width).toInt().coerceAtLeast(0),(edge(ys,row,false)*height).toInt().coerceAtLeast(0),
                (edge(xs,col,true)*width).toInt().coerceAtMost(width),(edge(ys,row,true)*height).toInt().coerceAtMost(height))
        }
        val cw=width*(1-side*2)/columns;val ch=height*(bottom-top)/rows
        return Rect((width*side+cw*(index%columns)).toInt(),(height*top+ch*(index/columns)).toInt(),
            (width*side+cw*(index%columns+1)).toInt(),(height*top+ch*(index/columns+1)).toInt())
    }
    fun slotAt(x:Float,y:Float,width:Int,height:Int):Int? {
        // Precise icon boxes take priority over inferred row/column boundaries.
        overrides.entries.firstOrNull { it.value.contains(x,y) }?.let { return it.key }
        return (0 until count).firstOrNull { slotBounds(it,width,height).contains(x.toInt(),y.toInt()) }
    }
    fun crop(index: Int, width: Int, height: Int): Rect {
        overrides[index]?.clipped(width,height)?.let { return Rect(it.left,it.top,it.right,it.bottom) }
        if(index>=columns*rows) return slotBounds(index,width,height)
        if(automatic) {
            val box=PixelBox.square(xs[index%columns]*width,ys[index/columns]*height,autoSize*width,width,height)
            return Rect(box.left,box.top,box.right,box.bottom)
        }
        val cellWidth = width * (1 - side * 2) / columns
        val cellHeight = height * (bottom - top) / rows
        val x = width * side + cellWidth * (index % columns + .5f)
        val y = height * top + cellHeight * (index / columns + centerY)
        val radius = min(cellWidth, cellHeight) * iconRatio / 2
        return Rect((x-radius).toInt().coerceAtLeast(0), (y-radius).toInt().coerceAtLeast(0),
            (x+radius).toInt().coerceAtMost(width), (y+radius).toInt().coerceAtMost(height))
    }
}

object VisualCheck {
    fun crop(bitmap: Bitmap, grid: GridConfig, index: Int): Bitmap {
        val r=grid.crop(index,bitmap.width,bitmap.height)
        return Bitmap.createBitmap(bitmap,r.left,r.top,r.width(),r.height())
    }
    fun fingerprint(bitmap: Bitmap): IntArray {
        // Only central area: avoid most wallpaper around the icon.
        val margin=(bitmap.width*.15f).toInt()
        val inner=Bitmap.createBitmap(bitmap,margin,margin,bitmap.width-2*margin,bitmap.height-2*margin)
        val small=Bitmap.createScaledBitmap(inner,12,12,true)
        return IntArray(144).also { small.getPixels(it,0,12,0,0,12,12) }
    }
    fun distance(a: IntArray, b: IntArray): Float {
        require(a.size==b.size && a.isNotEmpty())
        var sum=0L
        for(i in a.indices) for(shift in intArrayOf(0,8,16)) {
            sum+=abs(((a[i] shr shift) and 255)-((b[i] shr shift) and 255))
        }
        return sum.toFloat()/(a.size*3)
    }
}

data class DesktopCapture(val bitmap: Bitmap, val launcher: String, val rotation: Int,
    val nodes:List<DesktopNode> = emptyList(), val detection:DetectionResult?=null)
data class SortSession(
    val capture: DesktopCapture, val grid: GridConfig,
    val kinds: List<CellKind>, val ranks: Map<Int, ColorRank>,
    val fingerprints: Map<Int, IntArray>, val initial: Map<Int, Int?>,
    val target: Map<Int, Int?>, val moves: List<Move>
) {
    companion object {
        fun create(capture: DesktopCapture, grid: GridConfig, kinds: List<CellKind>): SortSession {
            require(kinds.size==grid.count)
            require(kinds.count { it==CellKind.ICON }>=2) { "请至少标记两个普通应用图标" }
            require(kinds.any { it==CellKind.EMPTY }) { "请至少标记一个真实空位，用于交换图标" }
            val ranks=mutableMapOf<Int,ColorRank>()
            val fingerprints=mutableMapOf<Int,IntArray>()
            for(i in kinds.indices) {
                val crop=VisualCheck.crop(capture.bitmap,grid,i)
                val sample=VisualCheck.fingerprint(crop)
                fingerprints[i]=sample
                if(kinds[i]==CellKind.ICON) ranks[i]=ColorRank.dominant(sample)
            }
            val initial=kinds.indices.filter { kinds[it]!=CellKind.FIXED }
                .associateWith { if(kinds[it]==CellKind.ICON) it else null }
            val sorted=ranks.keys.sortedWith(compareBy<Int> { ranks.getValue(it) }.thenBy { it })
            var cursor=0
            val target=initial.keys.associateWith { if(kinds[it]==CellKind.ICON) sorted[cursor++] else null }
            return SortSession(capture,grid,kinds.toList(),ranks,fingerprints,initial,target,
                MovePlanner.plan(initial,target))
        }
    }
}

/** Adapters hold behavior, never model-specific pixel coordinates. Unverified until device testing. */
data class LauncherAdapter(val name: String, val holdMillis: Long, val dragMillis: Long, val settleMillis: Long)
object LauncherAdapters {
    fun forPackage(pkg: String): LauncherAdapter = when {
        pkg.contains("bbk") || pkg.contains("vivo") -> LauncherAdapter("OriginOS · 实验适配",900,750,1600)
        pkg.contains("miui") -> LauncherAdapter("HyperOS / MIUI · 实验适配",850,750,1500)
        pkg.contains("sec.android") -> LauncherAdapter("One UI · 实验适配",850,800,1500)
        pkg.contains("oppo") || pkg.contains("coloros") || pkg.contains("oneplus") -> LauncherAdapter("ColorOS / OxygenOS · 实验适配",900,800,1500)
        pkg.contains("huawei") || pkg.contains("honor") -> LauncherAdapter("EMUI / MagicOS · 实验适配",900,800,1600)
        pkg.contains("nexuslauncher") || pkg.contains("launcher3") -> LauncherAdapter("Pixel / AOSP · 实验适配",850,750,1400)
        else -> LauncherAdapter("通用桌面 · 实验适配",1000,900,1800)
    }
}
