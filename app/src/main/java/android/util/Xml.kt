package android.util

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * DASH-AA platform shim — `android.util.Xml` on the desktop JVM.
 *
 * Android's framework parser *is* kXML2 underneath, so handing upstream's SvgParser the same
 * parser here is not an approximation: the panel artwork is read by the same code on both builds.
 */
object Xml {
    @JvmStatic
    fun newPullParser(): XmlPullParser = KXmlParser()
}
