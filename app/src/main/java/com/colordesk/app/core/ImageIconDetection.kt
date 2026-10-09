package com.colordesk.app.core

import kotlin.math.*

/** Local edge components: produces candidates, never certifies an app or an empty slot. */
object ImageIconDetection {
    fun detect(pixels:IntArray,width:Int,height:Int):List<PixelBox> {
        require(pixels.size==width*height && width>2 && height>2)
        val edges=BooleanArray(pixels.size)
        fun difference(a:Int,b:Int):Int = max(abs((a and 255)-(b and 255)),
            max(abs(((a shr 8) and 255)-((b shr 8) and 255)),abs(((a shr 16) and 255)-((b shr 16) and 255))))
        for(y in max(2,(height*.045f).toInt()) until min(height-2,(height*.88f).toInt())) for(x in 2 until width-2) {
            val i=y*width+x
            if(difference(pixels[i-1],pixels[i+1])>60 || difference(pixels[i-width],pixels[i+width])>60) {
                for(dy in -1..1) for(dx in -1..1) edges[(y+dy)*width+x+dx]=true
            }
        }
        val queue=IntArray(pixels.size)
        val boxes=mutableListOf<PixelBox>()
        for(start in edges.indices) {
            if(!edges[start]) continue
            var head=0;var tail=1;queue[0]=start;edges[start]=false
            var left=start%width;var right=left;var top=start/width;var bottom=top
            while(head<tail) {
                val i=queue[head++];val x=i%width;val y=i/width
                left=min(left,x);right=max(right,x);top=min(top,y);bottom=max(bottom,y)
                for(neighbor in intArrayOf(if(x>0) i-1 else -1,if(x<width-1) i+1 else -1,
                    if(y>0) i-width else -1,if(y<height-1) i+width else -1)) {
                    if(neighbor>=0 && edges[neighbor]) { edges[neighbor]=false;queue[tail++]=neighbor }
                }
            }
            val w=right-left+1;val h=bottom-top+1
            if(w in (width*.065f).toInt()..(width*.20f).toInt() && h in (w*.72f).toInt()..(w*1.38f).toInt() &&
                tail>=w*1.5f && top>height*.05f && bottom<height*.88f) {
                // Undo the edge dilation; geometry refers to the colored icon, not its label.
                boxes+=PixelBox(left+2,top+2,right-1,bottom-1)
            }
        }
        return boxes.sortedByDescending { it.width*it.height }.fold(mutableListOf<PixelBox>()) { out,box ->
            if(out.none { abs(it.x-box.x)<max(it.width,box.width)*.5f && abs(it.y-box.y)<max(it.height,box.height)*.5f }) out+=box
            out
        }.take(80)
    }
}
