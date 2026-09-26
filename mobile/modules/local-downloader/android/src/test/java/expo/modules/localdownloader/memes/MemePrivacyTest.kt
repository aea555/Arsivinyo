package expo.modules.localdownloader.memes

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `shared/memes/CONTRACT.md`, done criterion 5: no tag, caption or account reaches a log or
 * a notification. Read from the sources, so a new log line that names one fails here.
 */
class MemePrivacyTest {

  private fun module(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null && !File(dir, "mobile/modules/local-downloader").isDirectory) dir = dir.parentFile
    return File(dir ?: error("the repository root is not above ${System.getProperty("user.dir")}"),
      "mobile/modules/local-downloader/android/src/main")
  }

  private val sources get() = File(module(), "java/expo/modules/localdownloader")

  /** A log or error line, and what it interpolates. */
  private val logCall = Regex("""\b(Log\.[dwiev]|debug|addError|privateTrace)\(""")
  private val interpolated = Regex("""\$\{([^}]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")

  /** Only these may appear inside a meme log line: a type, a count, an id. */
  private val allowed = setOf("it.javaClass.simpleName", "error.javaClass.simpleName", "failed", "imported", "taskId")

  @Test
  fun noMemeCodeLogsWhatAMemeIs() {
    val files = listOf(File(sources, "memes/MemeCollection.kt"), File(sources, "memes/MemeStore.kt"),
      File(sources, "memes/MemeFaces.kt"), File(sources, "memes/FaceScanner.kt"), File(sources, "memes/FacesNative.kt"),
      File(sources, "pairing/SoundsContent.kt"))
    val module = File(sources, "LocalDownloaderModule.kt").readLines()
    // The module's meme code: every line that mentions a meme and logs.
    val lines = files.flatMap { it.readLines() } + module.filter { it.contains("MEME_") || it.contains("meme", ignoreCase = true) || it.contains("FACES_") }
    for (line in lines.filter { logCall.containsMatchIn(it) }) {
      for (match in interpolated.findAll(line)) {
        val expression = match.groupValues[1].ifEmpty { match.groupValues[2] }
        assertTrue("a meme log line interpolates `$expression`: ${line.trim()}", expression in allowed)
      }
    }
  }

  @Test
  fun theEngineResultIsRedactedBeforeItIsLogged() {
    val module = File(sources, "LocalDownloaderModule.kt").readText()
    val redaction = module.substringAfter("private fun redactedForLog").substringBefore("}")
    assertTrue("the source, which holds the caption and the account, is redacted", redaction.contains("\"source\""))
  }

  @Test
  fun thePromptNamesNothingAboutTheMeme() {
    for (values in listOf("values", "values-tr")) {
      val strings = File(module(), "res/$values/strings.xml").readText()
      for (name in listOf("ldl_meme_prompt_title", "ldl_meme_prompt_text")) {
        val value = Regex("""name="$name">([^<]*)<""").find(strings)?.groupValues?.get(1)
        assertTrue("$values has $name", value != null)
        // A format argument is the only way anything about the meme could be put in it.
        assertFalse("$values/$name takes no arguments", value!!.contains("%"))
      }
    }
    val collection = File(sources, "memes/MemeCollection.kt").readText()
    val builder = collection.substringAfter("NotificationCompat.Builder").substringBefore(".build()")
    for (field in listOf("caption", "account", "tags", "people", "source")) {
      assertFalse("the notification does not read the meme's $field", builder.contains(field))
    }
  }
}
