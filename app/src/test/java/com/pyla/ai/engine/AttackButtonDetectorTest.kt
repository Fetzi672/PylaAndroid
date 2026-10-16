package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File


class AttackButtonDetectorTest {
    private val w=320;private val h=180
    private fun background()=ByteArray(w*h*3).also { a -> for(i in 0 until w*h){a[i*3]=30;a[i*3+1]=90;a[i*3+2]=40} }
    private fun pixel(a:ByteArray,x:Int,y:Int,r:Int,g:Int,b:Int){val i=(y*w+x)*3;a[i]=r.toByte();a[i+1]=g.toByte();a[i+2]=b.toByte()}
    private fun ring(a:ByteArray,cx:Int,cy:Int,r:Int=20){for(y in 0 until h)for(x in 0 until w){val d=(x-cx)*(x-cx)+(y-cy)*(y-cy);if(d<=r*r){if(d<36)pixel(a,x,y,220,195,200)else pixel(a,x,y,160,12,20)}}}
    @Test fun relocatedRingHasItsOwnCenter(){for((x,y)in listOf(40 to 40,270 to 120,160 to 90)){val a=background();ring(a,x,y);val p=requireNotNull(AttackButtonPixels.detect(w,h,a));assertEquals(x.toFloat(),p.x,.6f);assertEquals(y.toFloat(),p.y,.6f)}}
    @Test fun twoPlausibleButtonsAreAmbiguous(){val a=background();ring(a,50,90);ring(a,250,90);assertNull(AttackButtonPixels.detect(w,h,a))}
    @Test fun greenMenuIsDenied(){assertNull(AttackButtonPixels.detect(w,h,background()))}
    @Test fun redHealthBarIsNotControl(){val a=background();for(y in 40..45)for(x in 100..180)pixel(a,x,y,160,12,20);assertNull(AttackButtonPixels.detect(w,h,a))}
    @Test fun filledRedSquareIsNotControl(){val a=background();for(y in 60..100)for(x in 140..180)pixel(a,x,y,160,12,20);assertNull(AttackButtonPixels.detect(w,h,a))}
    @Test fun hollowRedSquareIsNotCircularControl(){val a=background();for(y in 50..100)for(x in 140..190)if(x<152||x>178||y<62||y>88)pixel(a,x,y,160,12,20);assertNull(AttackButtonPixels.detect(w,h,a))}
    @Test fun tinyRedDotIsDenied(){val a=background();ring(a,160,90,7);assertNull(AttackButtonPixels.detect(w,h,a))}
    @Test fun blueControlCannotPassRedRule(){val a=background();ring(a,160,90);for(i in 0 until w*h){val r=a[3*i];a[3*i]=a[3*i+2];a[3*i+2]=r};assertNull(AttackButtonPixels.detect(w,h,a))}
    @Test fun malformedAndOversizedBuffersFailClosed(){assertNull(AttackButtonPixels.detect(w,h,ByteArray(10)));assertNull(AttackButtonPixels.detect(Int.MAX_VALUE,Int.MAX_VALUE,ByteArray(0)));assertNull(AttackButtonPixels.detect(-1,h,ByteArray(0)))}
    @Test fun exactProductionPixelsReplayScreenshotFixtureMatrix(){
        val dir=System.getenv("PYLA_ATTACK_RGB_FIXTURE_DIR")?.let(::File);assumeTrue("Private screenshot fixture directory explicitly required",dir?.isDirectory==true)
        val positive=listOf("real/video-025.0.png" to (1370f to 410f),"transformed/shift-leftup-025.png" to (968f to 247f),"transformed/shift-left-025.png" to (468f to 287f))
        fun detect(name:String):AttackButtonPoint? = AttackButtonPixels.detect(1560,720,File(dir,name+".rgb").readBytes())
        for((name,expected)in positive){val p=requireNotNull(detect(name)){"Missing genuine/translated control: $name"};assertEquals(expected.first,p.x,14f);assertEquals(expected.second,p.y,14f)}
        val attached=requireNotNull(detect("real/video-045.0.png"))
        assertEquals(1408f,attached.x,8f);assertEquals(468f,attached.y,8f)
        assertNull(detect("real/video-083.0.png"))
    }
}
