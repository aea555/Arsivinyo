package expo.modules.localdownloader.memes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import expo.modules.localdownloader.R
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The meme collection on the phone: [MemeStore] over a Keystore key, with files in
 * MediaStore under the owner model, the same way the music library holds its tracks.
 *
 * Private memes are not here. They are vault entries, and the module reads and writes their
 * labels through the vault's own listing.
 */
class MemeCollection(private val context: Context, privateHalf: MemeStore.PrivateHalf) {

  val store: MemeStore by lazy { MemeStore(File(context.filesDir, "memes/index.bin"), KeystoreSealer, privateHalf, FacesNative.orNull()) }

  private val thumbs: File get() = File(context.cacheDir, "meme-thumbs")

  /**
   * AES-GCM under a Keystore key that needs no authentication: search never prompts, and a
   * copy of the app's files cannot be read anywhere else.
   */
  private object KeystoreSealer : MemeStore.Sealer {
    private const val ALIAS = "arsivinyo_memes_index_v1"

    private fun key(): SecretKey {
      val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
      val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      generator.init(
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build()
      )
      return generator.generateKey()
    }

    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key())
      cipher.updateAAD(associatedData)
      val sealed = cipher.doFinal(plaintext)
      return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + sealed
    }

    override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
      val ivLength = sealed[0].toInt()
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 1, ivLength))
      cipher.updateAAD(associatedData)
      return cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    }
  }

  /**
   * A file just saved to MediaStore joins the collection. Null for anything that is not a
   * video or an image, which stays a plain download.
   */
  fun adopt(uri: String, mimeType: String, source: MemeStore.Source?): MemeStore.Item? {
    val kind = kindOf(mimeType) ?: return null
    val sha = hashOf { context.contentResolver.openInputStream(Uri.parse(uri)) } ?: return null
    return store.add(uri, kind, sha, source)
  }

  /** The labels by name, which is how they travel, with the faces of the people in them. */
  fun meme(item: MemeStore.Item): org.json.JSONObject {
    val snapshot = store.snapshot()
    val (tags, people) = store.labelsOf(item, snapshot)
    return MemeStore.encodeMeme(item.kind, item.source, tags, people, MemeStore.signaturesOf(people, snapshot))
  }

  private val faceCrops: File get() = File(context.cacheDir, "meme-faces")

  /**
   * A face cut from a meme that is not private, cached beside the grid's thumbnails. A
   * private meme's faces are cut by the module from the vault and never written here.
   */
  @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
  fun faceCrop(item: MemeStore.Item, face: Face): String? {
    if (item.isPrivate) return null
    val uri = item.uri ?: return null
    val cached = File(faceCrops, "${face.id}.jpg")
    if (cached.isFile) return cached.toURI().toString()
    val frame = frame(item, face.frameMs) ?: return null
    val crop = FaceScanner.crop(frame, face.box) ?: return null
    faceCrops.mkdirs()
    cached.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    return cached.toURI().toString()
  }

  /** A public meme's frame, taken the way scanning takes it. */
  @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
  fun frame(item: MemeStore.Item, ms: Int): Bitmap? {
    val uri = Uri.parse(item.uri ?: return null)
    if (item.kind != "video") return FaceScanner.image(android.graphics.ImageDecoder.createSource(context.contentResolver, uri))
    val retriever = android.media.MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      FaceScanner.frameOfVideo(retriever, ms)
    } catch (_: Exception) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  /**
   * A public meme's faces, for a scan. [found] gets them with the frames they were seen in,
   * while those are still decoded, so the faces can be cut out without decoding again.
   */
  @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
  fun scan(
    item: MemeStore.Item,
    scanner: FaceScanner,
    found: (List<ScannedFace>, Map<Int, Bitmap>) -> Unit = { _, _ -> },
  ): List<ScannedFace> {
    val uri = Uri.parse(item.uri ?: return emptyList())
    val frames: List<Pair<Bitmap, Int>> = if (item.kind != "video") {
      listOfNotNull(FaceScanner.image(android.graphics.ImageDecoder.createSource(context.contentResolver, uri))?.let { it to 0 })
    } else {
      val retriever = android.media.MediaMetadataRetriever()
      try {
        retriever.setDataSource(context, uri)
        scanner.frames(retriever)
      } finally {
        runCatching { retriever.release() }
      }
    }
    val faces = scanner.scan(frames)
    found(faces, frames.associate { (frame, ms) -> ms to frame })
    return faces
  }

  /** Cuts out and keeps the faces of a public meme from frames already decoded. */
  @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
  fun cacheFaceCrops(item: MemeStore.Item, frames: Map<Int, Bitmap>) {
    if (item.isPrivate) return
    faceCrops.mkdirs()
    for (face in item.faces) {
      val cached = File(faceCrops, "${face.id}.jpg")
      if (cached.isFile) continue
      val crop = frames[face.frameMs]?.let { FaceScanner.crop(it, face.box) } ?: continue
      runCatching { cached.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
    }
  }

  fun open(item: MemeStore.Item): InputStream? =
    item.uri?.let { context.contentResolver.openInputStream(Uri.parse(it)) }

  fun sizeOf(item: MemeStore.Item): Long = item.uri?.let { uri ->
    runCatching {
      context.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) it.getLong(0) else 0L
      }
    }.getOrNull()
  } ?: 0L

  fun displayName(item: MemeStore.Item): String = item.uri?.let { uri ->
    runCatching {
      context.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
      }
    }.getOrNull()
  } ?: "${item.id}.${if (item.kind == "image") "jpg" else "mp4"}"

  /** Drops the memes whose files were deleted outside the app. */
  fun reconcile(): Int = store.reconcile { item ->
    val uri = item.uri ?: return@reconcile true
    runCatching {
      context.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 }
    }.getOrNull() ?: true
  }

  /** Deletes the file and the entry. The app owns the file, so no confirmation is needed. */
  fun delete(item: MemeStore.Item) {
    item.uri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
    File(thumbs, "${item.id}.jpg").delete()
    forgetFaceCrops(item)
    store.remove(item.id)
  }

  /**
   * A meme gone into the vault leaves no picture of itself outside it. Also run over every
   * private meme on listing, for thumbnails an earlier build left behind.
   */
  fun forgetThumbnail(itemId: String) {
    File(thumbs, "$itemId.jpg").delete()
  }

  /** The same for its faces. */
  fun forgetFaceCrops(item: MemeStore.Item) {
    item.faces.forEach { File(faceCrops, "${it.id}.jpg").delete() }
  }

  /** The grid's picture: made once, kept in the cache, never for a private meme. */
  fun thumbnail(item: MemeStore.Item): String? {
    if (item.isPrivate) return null
    val uri = item.uri ?: return null
    val cached = File(thumbs, "${item.id}.jpg")
    if (cached.isFile) return cached.toURI().toString()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
      val bitmap = context.contentResolver.loadThumbnail(Uri.parse(uri), Size(480, 480), null)
      thumbs.mkdirs()
      cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
      cached.toURI().toString()
    }.getOrNull()
  }

  /**
   * The quick prompt: a notification that says only that a meme finished downloading. The
   * caption, the account and every label stay out of it; the tap opens the app on the meme.
   */
  fun prompt(item: MemeStore.Item) {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, context.getString(R.string.ldl_memes_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
          description = context.getString(R.string.ldl_memes_channel_description)
          lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
      )
    }
    val open = Intent(Intent.ACTION_VIEW, Uri.parse("arsivinyo://memes?prompt=${item.id}")).apply {
      setPackage(context.packageName)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(context.applicationInfo.icon)
      .setContentTitle(context.getString(R.string.ldl_meme_prompt_title))
      .setContentText(context.getString(R.string.ldl_meme_prompt_text))
      .setAutoCancel(true)
      .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
      .setContentIntent(PendingIntent.getActivity(context, item.id.hashCode(), open,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
      .build()
    runCatching { manager.notify(PROMPT_TAG, item.id.hashCode(), notification) }
  }

  fun dismissPrompt(itemId: String) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    manager.cancel(PROMPT_TAG, itemId.hashCode())
  }

  companion object {
    private const val CHANNEL_ID = "arsivinyo_memes"
    private const val PROMPT_TAG = "meme-prompt"

    fun kindOf(mimeType: String): String? = when {
      mimeType.startsWith("video/") -> "video"
      mimeType.startsWith("image/") -> "image"
      else -> null
    }

    fun hashOf(open: () -> InputStream?): String? = runCatching {
      val digest = MessageDigest.getInstance("SHA-256")
      open()?.use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      } ?: return null
      digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
  }
}
