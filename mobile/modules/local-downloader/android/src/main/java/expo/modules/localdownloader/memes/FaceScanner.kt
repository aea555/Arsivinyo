package expo.modules.localdownloader.memes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Finds and recognises the faces in a meme: shared/faces over its frames.
 *
 * Frames are taken the same way for scanning and for showing a face afterwards
 * ([frameOfVideo], [image]), so a stored box lands on the face it was found at.
 *
 * Android 9 and later: frames are decoded with ImageDecoder, which turns a photo upright.
 */
@RequiresApi(Build.VERSION_CODES.P)
class FaceScanner private constructor(private val handle: Long) : Closeable {

  companion object {
    /** The longest side a frame is taken at, as on the Mac. */
    const val FRAME_LIMIT = 1280

    /** Per sighting from nativeLook: x, y, w, h, score, landmarks[10], frameMs, signature[128]. */
    private const val SIGHTING = 16 + 128
    /** Per face from nativeMerge: x, y, w, h, score, frameMs, sightings, signature[128]. */
    private const val FACE = 7 + 128

    /**
     * Loads both models from the app's assets, refusing either if it is not the file
     * MODELS.json pins: another model's signatures would match nothing already stored.
     */
    fun load(context: Context): FaceScanner {
      check(FacesNative.available) { "FACES_UNAVAILABLE" }
      val assets = context.assets
      val manifest = JSONObject(assets.open("faces/MODELS.json").use { String(it.readBytes(), Charsets.UTF_8) })
      fun model(key: String): ByteArray {
        val entry = manifest.getJSONObject(key)
        val bytes = assets.open("faces/" + entry.getString("file")).use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == entry.getString("sha256")) { "FACES_MODEL_MISMATCH" }
        return bytes
      }
      return FaceScanner(FacesNative.nativeLoad(model("detector"), model("recogniser")))
    }

    /** An image upright, at most FRAME_LIMIT on its long side, in pixels getPixels can read. */
    fun image(source: ImageDecoder.Source): Bitmap? = runCatching {
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > FRAME_LIMIT) {
          val scale = FRAME_LIMIT.toFloat() / longest
          decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        }
      }
    }.getOrNull()

    fun image(bytes: ByteArray): Bitmap? = image(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))

    /** The frame of a video at a time, upright, at most FRAME_LIMIT on its long side. */
    fun frameOfVideo(retriever: MediaMetadataRetriever, ms: Int): Bitmap? {
      val us = ms * 1000L
      val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
      val longest = maxOf(width, height)
      val frame = if (longest > FRAME_LIMIT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val scale = FRAME_LIMIT.toFloat() / longest
        retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
          (width * scale).toInt(), (height * scale).toInt())
      } else {
        retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
      } ?: return null
      // Some devices hand the frame over as stored rather than as shown; turn it upright.
      val shownWide = if (rotation % 180 == 0) width >= height else height >= width
      if (rotation % 360 == 0 || (frame.width >= frame.height) == shownWide) return frame
      return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    /** A face cut out of its frame with some margin, for showing. */
    fun crop(frame: Bitmap, box: List<Double>, margin: Double = 0.35): Bitmap? {
      if (box.size != 4) return null
      val side = maxOf(box[2], box[3]) * (1 + margin * 2)
      val cx = box[0] + box[2] / 2
      val cy = box[1] + box[3] / 2
      val left = (cx - side / 2).toInt().coerceIn(0, frame.width - 1)
      val top = (cy - side / 2).toInt().coerceIn(0, frame.height - 1)
      val right = (cx + side / 2).toInt().coerceIn(left + 1, frame.width)
      val bottom = (cy + side / 2).toInt().coerceIn(top + 1, frame.height)
      return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }
  }

  private val lock = Any()

  /** Every face worth keeping in one frame, as flat records. */
  private fun look(frame: Bitmap, frameMs: Int): FloatArray {
    val pixels = IntArray(frame.width * frame.height)
    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
    return synchronized(lock) { FacesNative.nativeLook(handle, pixels, frame.width, frame.height, frameMs) }
  }

  /** A meme's faces: its frames looked at, then the sightings of each person merged. */
  fun scan(frames: List<Pair<Bitmap, Int>>): List<ScannedFace> {
    val sightings = frames.map { (frame, ms) -> look(frame, ms) }
    val all = FloatArray(sightings.sumOf { it.size })
    var at = 0
    sightings.forEach { it.copyInto(all, at); at += it.size }
    if (all.isEmpty()) return emptyList()
    val merged = FacesNative.nativeMerge(all)
    return (0 until merged.size / FACE).map { i ->
      val base = i * FACE
      ScannedFace(
        signature = merged.copyOfRange(base + 7, base + FACE),
        box = listOf(merged[base].toDouble(), merged[base + 1].toDouble(), merged[base + 2].toDouble(), merged[base + 3].toDouble()),
        frameMs = merged[base + 5].toInt(),
      )
    }
  }

  /** A video, from wherever the retriever was pointed. */
  fun scan(retriever: MediaMetadataRetriever): List<ScannedFace> {
    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
    val frames = FacesNative.nativeSampleTimes(duration).toList().mapNotNull { ms -> frameOfVideo(retriever, ms)?.let { it to ms } }
    return scan(frames)
  }

  override fun close() {
    FacesNative.nativeFree(handle)
  }
}
