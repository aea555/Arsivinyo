package expo.modules.localdownloader.memes

import android.util.Base64
import android.util.Log

/**
 * shared/faces through JNI (src/main/cpp/faces_jni.cpp): the same C++ the Mac compiles, over
 * ONNX Runtime, so signatures made here match signatures made there.
 */
object FacesNative : FaceMath {

  private const val SIGNATURE = 128

  /** Whether the libraries loaded. Without them faces are kept but not matched. */
  val available: Boolean = runCatching {
    System.loadLibrary("onnxruntime")
    System.loadLibrary("faces")
  }.onFailure { Log.w("Faces", "the faces libraries did not load: ${it.javaClass.simpleName}") }.isSuccess

  fun orNull(): FaceMath? = if (available) this else null

  external fun nativeLoad(detector: ByteArray, recogniser: ByteArray): Long
  external fun nativeFree(handle: Long)
  external fun nativeLook(handle: Long, argb: IntArray, width: Int, height: Int, frameMs: Int): FloatArray
  external fun nativeMerge(sightings: FloatArray): FloatArray
  private external fun nativeBest(signature: FloatArray, set: FloatArray): Float
  private external fun nativeAddToSet(set: FloatArray, signature: FloatArray): FloatArray
  private external fun nativeEncode(signature: FloatArray): ByteArray
  private external fun nativeDecode(bytes: ByteArray): FloatArray?
  external fun nativeSampleTimes(durationMs: Int): IntArray
  private external fun nativeSure(): Float
  private external fun nativeAsk(): Float
  private external fun nativeVersion(): Int

  override val sure: Float get() = nativeSure()
  override val ask: Float get() = nativeAsk()
  override val pipelineVersion: Int get() = nativeVersion()

  private fun flat(set: List<FloatArray>): FloatArray {
    val out = FloatArray(set.size * SIGNATURE)
    set.forEachIndexed { i, s -> s.copyInto(out, i * SIGNATURE, 0, SIGNATURE) }
    return out
  }

  override fun cosine(a: FloatArray, b: FloatArray): Float = nativeBest(a, b)

  override fun best(signature: FloatArray, set: List<FloatArray>): Float =
    if (set.isEmpty()) -1f else nativeBest(signature, flat(set))

  override fun addToSet(set: List<FloatArray>, signature: FloatArray): List<FloatArray> {
    val out = nativeAddToSet(flat(set), signature)
    return (0 until out.size / SIGNATURE).map { out.copyOfRange(it * SIGNATURE, (it + 1) * SIGNATURE) }
  }

  override fun encode(signature: FloatArray): String = Base64.encodeToString(nativeEncode(signature), Base64.NO_WRAP)

  override fun decode(text: String): FloatArray? =
    runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()?.let { nativeDecode(it) }
}
