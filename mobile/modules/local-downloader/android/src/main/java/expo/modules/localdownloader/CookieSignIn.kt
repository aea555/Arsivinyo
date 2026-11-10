package expo.modules.localdownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Signing in to a site inside the app, to take the session's cookies as a cookie profile:
 * the easy way to what importing a cookies.txt does. A window with the site's own sign-in page
 * and two buttons; Done takes the cookies, if the site's session cookie is among them.
 *
 * Android gives no app another app's cookies, so the browser's cannot be read: the sign-in
 * has to happen here. The password goes to the site and is never seen by the app. The cookies
 * are handed back as Netscape cookie text, which the module encrypts like an imported file;
 * the window's own copy of them, and anything else the site stored, is wiped as it closes, so
 * the session is nowhere in the clear. YouTube is not offered: Google refuses to sign in from
 * a window inside an app.
 */
internal object CookieSignIn {

  /** Where to sign in, the cookie domains to keep, and the cookie that means "signed in". */
  class Site(val start: String, val pages: List<String>, val session: String)

  val sites = mapOf(
    "instagram" to Site("https://www.instagram.com/accounts/login/", listOf("https://www.instagram.com/"), "sessionid"),
    "facebook" to Site("https://m.facebook.com/login/", listOf("https://www.facebook.com/", "https://m.facebook.com/"), "c_user"),
    "twitter" to Site("https://x.com/i/flow/login", listOf("https://x.com/", "https://twitter.com/"), "auth_token"),
    "reddit" to Site("https://www.reddit.com/login/", listOf("https://www.reddit.com/"), "reddit_session"),
    "tiktok" to Site("https://www.tiktok.com/login", listOf("https://www.tiktok.com/"), "sessionid"),
  )

  /** Shows the window. [done] gets the cookie text, or null when it was closed without one. */
  @SuppressLint("SetJavaScriptEnabled")
  fun show(activity: Activity, platform: String, done: (String?) -> Unit) {
    val site = sites[platform] ?: return done(null)
    val cookies = CookieManager.getInstance()
    // A session of its own: nothing left from a sign-in before this one.
    cookies.removeAllCookies(null)

    val web = WebView(activity)
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.webViewClient = WebViewClient()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(web, true)

    val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
    // A sign-in page is not for screenshots or the recents list.
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    var result: String? = null

    val bar = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setBackgroundColor(Color.BLACK)
      val pad = (8 * resources.displayMetrics.density).toInt()
      setPadding(pad, pad, pad, pad)
    }
    val cancel = Button(activity).apply {
      text = activity.getString(R.string.ldl_cookie_signin_cancel)
      setOnClickListener { dialog.dismiss() }
    }
    val title = TextView(activity).apply {
      text = activity.getString(R.string.ldl_cookie_signin_title)
      setTextColor(Color.WHITE)
      gravity = Gravity.CENTER
    }
    val finish = Button(activity).apply {
      text = activity.getString(R.string.ldl_cookie_signin_done)
      setOnClickListener {
        cookies.flush()
        val text = cookieText(site, cookies)
        if (text == null) {
          Toast.makeText(activity, R.string.ldl_cookie_signin_not_yet, Toast.LENGTH_LONG).show()
        } else {
          result = text
          dialog.dismiss()
        }
      }
    }
    bar.addView(cancel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    bar.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    bar.addView(finish, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

    val root = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      fitsSystemWindows = true
      setBackgroundColor(Color.BLACK)
      addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
      addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }
    dialog.setContentView(root)
    dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    // Back goes back in the site first, and only then closes the window.
    dialog.setOnKeyListener { _, key, event ->
      if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && web.canGoBack()) {
        web.goBack()
        true
      } else {
        false
      }
    }
    dialog.setOnDismissListener {
      web.stopLoading()
      web.destroy()
      cookies.removeAllCookies(null)
      cookies.flush()
      WebStorage.getInstance().deleteAllData()
      done(result)
    }
    dialog.show()
    web.loadUrl(site.start)
  }

  /**
   * The site's cookies as a Netscape cookie file, or null when its session cookie is not
   * there, which is to say nobody is signed in. Android gives a cookie's name and value and
   * nothing else, so each is written for the whole domain, secure, and good for a year: the
   * site ends the session sooner if it means to.
   */
  private fun cookieText(site: Site, cookies: CookieManager): String? {
    val expires = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
    val lines = LinkedHashMap<String, String>()
    var signedIn = false
    for (page in site.pages) {
      val domain = "." + page.removePrefix("https://").trimEnd('/').removePrefix("www.").removePrefix("m.")
      for (pair in cookies.getCookie(page).orEmpty().split(';')) {
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        if (name.isEmpty() || name.contains('\t') || value.contains('\t')) continue
        if (name == site.session && value.isNotEmpty()) signedIn = true
        lines["$domain\t$name"] = "$domain\tTRUE\t/\tTRUE\t$expires\t$name\t$value"
      }
    }
    if (!signedIn) return null
    return "# Netscape HTTP Cookie File\n" + lines.values.joinToString("\n") + "\n"
  }
}
