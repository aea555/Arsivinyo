package expo.modules.localdownloader.torrent

import android.util.Log

/**
 * shared/torrent through JNI (src/main/cpp/torrent_jni.cpp): the same engine, over the same
 * libtorrent, as the Mac. Handles are sessions; torrents are named by their info hash.
 */
object TorrentNative {

  val available: Boolean = runCatching { System.loadLibrary("torrent") }
    .onFailure { Log.w("Torrent", "the torrent library did not load: ${it.javaClass.simpleName}") }.isSuccess

  external fun nativeCreate(stateDir: String, port: Int, ratio: Double, upload: Boolean, discovery: Boolean,
                            uploadLimit: Int, downloadLimit: Int): Long
  external fun nativeDestroy(handle: Long)
  external fun nativeApply(handle: Long, stateDir: String, port: Int, ratio: Double, upload: Boolean, discovery: Boolean,
                           uploadLimit: Int, downloadLimit: Int)
  external fun nativePort(handle: Long): Int
  external fun nativeAddMagnet(handle: Long, magnet: String, savePath: String, paused: Boolean): String?
  external fun nativeAddTorrent(handle: Long, data: ByteArray, savePath: String, paused: Boolean): String?
  external fun nativeRemove(handle: Long, id: String, deleteFiles: Boolean): Int
  external fun nativePause(handle: Long, id: String): Int
  external fun nativeResume(handle: Long, id: String): Int
  /** Fetches no pieces but keeps its peers, while its files are being chosen. */
  external fun nativeHold(handle: Long, id: String, on: Boolean): Int
  external fun nativeConnectPeer(handle: Long, id: String, ip: String, port: Int): Int
  external fun nativeFiles(handle: Long, id: String): String?
  /** JSON [{index, done, size, priority}], or null before metadata. */
  external fun nativeFileProgress(handle: Long, id: String): String?
  /** A held torrent's files chosen: it is added again, fresh, with these priorities. */
  external fun nativeChoose(handle: Long, id: String, priorities: ByteArray): Int
  external fun nativeSetPriorities(handle: Long, id: String, priorities: ByteArray): Int
  external fun nativeStatus(handle: Long): String?
  external fun nativeTorrentFile(handle: Long, id: String): ByteArray?
  external fun nativeFilePath(handle: Long, id: String, file: Int): String?
  external fun nativeServerStart(handle: Long): String?
  /** JSON {id, file, size, path}, or null with the engine's code in `code[0]`. */
  external fun nativeStream(handle: Long, magnet: String, cacheDir: String, file: Int, nameHint: String?, timeoutMs: Int,
                            code: IntArray): String?
  external fun nativeCacheTrim(handle: Long, cacheDir: String, limitBytes: Long, keepId: String?): Int

  /** shared/torrent/torrent.h's codes. */
  const val NO_METADATA = -3
}
