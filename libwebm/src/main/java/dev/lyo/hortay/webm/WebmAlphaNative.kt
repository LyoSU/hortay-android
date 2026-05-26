package dev.lyo.hortay.webm

/** JNI surface for the minimal ffmpeg VP9+alpha WebM decoder. Single shot: decode a whole
 *  short loop to RGBA frames. The native library (libhortaywebm.so) is built separately;
 *  these are only the declarations. */
object WebmAlphaNative {
    init { System.loadLibrary("hortaywebm") }

    /** Decodes [path] into RGBA frames scaled to [outW]x[outH] (<=0 keeps intrinsic).
     *  Returns null when the file is missing, not VP9, or carries no frames. */
    @JvmStatic external fun nativeDecode(path: String, outW: Int, outH: Int): Raw?

    /** Flat decode result. pixels = count*width*height ints, ARGB_8888-packed, frame-major. */
    class Raw(
        @JvmField val pixels: IntArray,
        @JvmField val delays: IntArray,
        @JvmField val count: Int,
        @JvmField val width: Int,
        @JvmField val height: Int,
    )
}
