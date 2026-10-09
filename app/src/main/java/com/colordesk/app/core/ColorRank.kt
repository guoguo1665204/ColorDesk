package com.colordesk.app.core

import kotlin.math.*

data class ColorRank(val group: Int, val hue: Float, val value: Float, val rgb: Int) : Comparable<ColorRank> {
    override fun compareTo(other: ColorRank): Int =
        compareValuesBy(this, other, { it.group }, { it.hue }, { it.value })
    val label: String get() = when(group) {
        1 -> "黑白灰"
        else -> when {
            hue < 20 || hue >= 345 -> "红"
            hue < 45 -> "橙"
            hue < 75 -> "黄"
            hue < 170 -> "绿"
            hue < 260 -> "蓝"
            else -> "紫"
        }
    }
    companion object {
        fun fromRgb(rgb: Int): ColorRank {
            val r=((rgb shr 16) and 255)/255f
            val g=((rgb shr 8) and 255)/255f
            val b=(rgb and 255)/255f
            val max=max(r,max(g,b)); val min=min(r,min(g,b)); val d=max-min
            val saturation=if(max==0f) 0f else d/max
            var hue=if(d==0f) 0f else when(max) {
                r -> 60f * (((g-b)/d) % 6f)
                g -> 60f * ((b-r)/d + 2f)
                else -> 60f * ((r-g)/d + 4f)
            }
            if(hue<0) hue+=360f
            // Wrap pink/red around the beginning rather than the end of the rainbow.
            val orderHue=if(hue>=345f) hue-360f else hue
            return ColorRank(if(saturation<0.16f || max<0.12f) 1 else 0,
                if(saturation<0.16f || max<0.12f) 0f else orderHue, max, rgb)
        }
        fun dominant(pixels: IntArray): ColorRank {
            require(pixels.isNotEmpty())
            val bins=linkedMapOf<Int, MutableList<Int>>()
            for(p in pixels) {
                val rank=fromRgb(p)
                val key=if(rank.group==1) 24+(rank.value*4).toInt().coerceAtMost(3)
                    else (((rank.hue+360)%360)/15).toInt()
                bins.getOrPut(key){mutableListOf()}.add(p)
            }
            val bucket=bins.maxBy { it.value.size }.value
            var r=0L; var g=0L; var b=0L
            for(p in bucket){r+=(p shr 16) and 255;g+=(p shr 8) and 255;b+=p and 255}
            val n=bucket.size
            return fromRgb(0xff000000.toInt() or ((r/n).toInt() shl 16) or ((g/n).toInt() shl 8) or (b/n).toInt())
        }
    }
}
