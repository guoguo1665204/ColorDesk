package com.colordesk.app.core

import org.junit.Assert.*
import org.junit.Test

class DetectionTest {
    private fun node(name:String,box:PixelBox,id:String="launcher:id/icon",ancestors:String="android.widget.FrameLayout")=
        DesktopNode(box,name,"",id,"android.widget.TextView",true,true,ancestors)
    private fun icon(name:String,x:Int,y:Int)=LocatedIcon(name,PixelBox(x-40,y-40,x+40,y+40),"测试",true)

    @Test fun nonDefaultSpacingUsesActualCoordinates() {
        val icons=listOf(icon("A",100,210),icon("B",350,210),icon("C",620,440),icon("D",100,710))
        val result=LayoutDetection.infer(icons,800,1200)!!
        assertEquals(3,result.xs.size);assertEquals(3,result.ys.size)
        assertEquals(350/800f,result.xs[1],.0001f)
        assertEquals(710/1200f,result.ys[2],.0001f)
        assertEquals(620,result.icons.getValue(5).box.x.toInt())
    }
    @Test fun screenshotBoxesRefineTextOnlyNodes() {
        val visual=listOf(PixelBox(80,200,160,280),PixelBox(280,200,360,280))
        val nodes=listOf(node("微信",PixelBox(75,290,165,315)),node("相机",PixelBox(275,290,365,315)))
        val result=LayoutDetection.detect(nodes,setOf("微信","相机"),visual,800,1400)
        assertNotNull(result.layout)
        assertEquals(visual[0],result.layout!!.icons.getValue(0).box)
        assertTrue(result.layout!!.icons.values.all { it.confirmedApp && it.source.contains("边缘") })
    }
    @Test fun androidWidgetNamespaceDoesNotExcludeOrdinaryTextViews() {
        val nodes=listOf(node("A",PixelBox(70,200,170,310)),node("B",PixelBox(270,200,370,310)))
        val result=LayoutDetection.detect(nodes,setOf("A","B"),emptyList(),800,1400)
        assertEquals(2,result.layout!!.icons.size)
    }
    @Test fun nativeImageBoundsTakePriorityOverEstimatesAndScreenshot() {
        val parents=listOf(node("A",PixelBox(60,180,180,320)),node("B",PixelBox(260,180,380,320)))
        val images=listOf(node("",PixelBox(80,200,160,280)).copy(className="android.widget.ImageView"),
            node("",PixelBox(280,200,360,280)).copy(className="android.widget.ImageView"))
        val result=LayoutDetection.detect(parents+images,setOf("A","B"),emptyList(),800,1400)
        assertEquals(images[0].bounds,result.layout!!.icons.getValue(0).box)
        assertEquals("桌面图像节点",result.layout!!.icons.getValue(0).source)
    }
    @Test fun folderWidgetAndDockAreExcludedEvenWithMatchingAppNames() {
        val nodes=listOf(node("A",PixelBox(70,200,170,310),ancestors="launcher.widget.AppWidgetHostView"),
            node("B",PixelBox(270,200,370,310),ancestors="launcher.FolderIcon"),
            node("C",PixelBox(470,200,570,310),ancestors="launcher:id/hotseat"))
        assertNull(LayoutDetection.detect(nodes,setOf("A","B","C"),emptyList(),800,1400).layout)
    }
    @Test fun duplicateTextAndDescriptionNodesDoNotCreateDuplicateSlots() {
        val visuals=listOf(PixelBox(80,200,160,280),PixelBox(280,200,360,280))
        val nodes=listOf(node("A",PixelBox(70,190,170,315)),node("A",PixelBox(75,290,165,315)),node("B",PixelBox(270,190,370,315)))
        val result=LayoutDetection.detect(nodes,setOf("A","B"),visuals,800,1400)
        assertEquals(2,result.layout!!.icons.size)
    }
    @Test fun unobservedSlotsRemainUnconfirmed() {
        val result=LayoutDetection.infer(listOf(icon("A",100,200),icon("B",300,200),icon("C",500,400)),800,1200)!!
        assertEquals(6,result.xs.size*result.ys.size)
        assertEquals(3,result.icons.size)
        assertFalse(result.icons.containsKey(3))
    }
    @Test fun observedInteriorGapCanBeInferredWithoutInventingApp() {
        val result=LayoutDetection.infer(listOf(icon("A",100,200),icon("B",300,200),icon("C",700,200)),900,1200)!!
        assertEquals(4,result.xs.size)
        assertFalse(result.icons.containsKey(2))
    }
    @Test fun visualFallbackNeverCertifiesAnApplication() {
        val result=LayoutDetection.detect(emptyList(),emptySet(),listOf(PixelBox(70,200,150,280),PixelBox(270,200,350,280)),800,1400)
        assertNotNull(result.layout)
        assertTrue(result.layout!!.icons.values.none { it.confirmedApp })
    }
    @Test fun nativeImagePositionsCanStillBeUsedWhenAppCatalogIsUnavailable() {
        val nodes=listOf(node("",PixelBox(70,200,150,280)).copy(className="android.widget.ImageView"),
            node("",PixelBox(270,200,350,280)).copy(className="android.widget.ImageView"))
        val result=LayoutDetection.detect(nodes,emptySet(),emptyList(),800,1400)
        assertEquals(2,result.layout!!.icons.size)
        assertTrue(result.layout!!.icons.values.none { it.confirmedApp })
    }
    @Test fun hiddenOrOutOfScreenPositionsAreIgnored() {
        val nodes=listOf(node("A",PixelBox(-100,200,-10,280)),node("B",PixelBox(100,1300,180,1380)))
        assertNull(LayoutDetection.detect(nodes,setOf("A","B"),emptyList(),800,1400).layout)
    }
    @Test fun blankScreenshotHasNoIcons() {
        assertTrue(ImageIconDetection.detect(IntArray(320*640){0xff888888.toInt()},320,640).isEmpty())
    }
    @Test fun imageCandidatesFindColoredSquaresWithoutIncludingLabels() {
        val w=320;val h=640
        val pixels=IntArray(w*h){0xff17212b.toInt()}
        val centers=listOf(60 to 150,140 to 150,220 to 150,60 to 280,140 to 280,220 to 280)
        for((index,center) in centers.withIndex()) {
            val (cx,cy)=center
            for(y in cy-18 until cy+18) for(x in cx-18 until cx+18) pixels[y*w+x]=if(index%2==0) 0xffee5555.toInt() else 0xff3399ff.toInt()
            // Label is a thin horizontal shape beneath the icon.
            for(y in cy+27 until cy+31) for(x in cx-20 until cx+20) pixels[y*w+x]=0xffeeeeee.toInt()
        }
        val result=ImageIconDetection.detect(pixels,w,h)
        assertEquals(6,result.size)
        for((x,y) in centers) assertTrue(result.any { kotlin.math.abs(it.x-x)<3 && kotlin.math.abs(it.y-y)<3 })
    }
    @Test fun busyNoiseWallpaperIsNotAcceptedAsManyApps() {
        val random=kotlin.random.Random(6)
        val pixels=IntArray(320*640) { 0xff000000.toInt() or random.nextInt(0xffffff) }
        assertTrue(ImageIconDetection.detect(pixels,320,640).isEmpty())
    }
    @Test fun badgeDescriptionCanMatchAnAppButNamePrefixesCannot() {
        val nodes=listOf(node("",PixelBox(70,200,170,310)).copy(description="微信，1条未读消息"),
            node("相机",PixelBox(270,200,370,310)),node("微信工具箱",PixelBox(470,200,570,310)))
        val result=LayoutDetection.detect(nodes,setOf("微信","相机"),emptyList(),800,1400)
        assertEquals(2,result.layout!!.icons.size)
    }
}
