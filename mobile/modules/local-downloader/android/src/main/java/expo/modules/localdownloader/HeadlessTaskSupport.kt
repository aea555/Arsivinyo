package expo.modules.localdownloader

import com.facebook.fbreact.specs.NativeHeadlessJsTaskSupportSpec
import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.jstasks.HeadlessJsTaskContext
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * How JavaScript says a headless task is done, which React Native leaves out of the new
 * architecture: its own module for this is only in the old one's package list (0.81), so there
 * JavaScript finds no such module and says nothing.
 *
 * It matters because of the music player. Its service starts one headless task, to hand over
 * the lock screen's buttons, and that task is over at once. Never told so, React Native held it
 * to be running for as long as the app lived, and while a task runs it keeps its timers going
 * behind the app: its timer callback ran on every frame of the screen, about a tenth of a
 * processor core (measured: 123 wake-ups a second at 120 Hz), and every timer in the app kept
 * firing unseen, which is what left the full player seconds behind the song.
 *
 * To be removed when React Native registers its own (HeadlessJsTaskSupportModule) for the new
 * architecture. app.plugin.js adds the package to MainApplication.
 */
@ReactModule(name = NativeHeadlessJsTaskSupportSpec.NAME)
class HeadlessTaskSupportModule(context: ReactApplicationContext) : NativeHeadlessJsTaskSupportSpec(context) {

  override fun notifyTaskRetry(taskId: Double, promise: Promise) {
    val tasks = HeadlessJsTaskContext.getInstance(reactApplicationContext)
    promise.resolve(tasks.isTaskRunning(taskId.toInt()) && tasks.retryTask(taskId.toInt()))
  }

  override fun notifyTaskFinished(taskId: Double) {
    val tasks = HeadlessJsTaskContext.getInstance(reactApplicationContext)
    if (tasks.isTaskRunning(taskId.toInt())) tasks.finishTask(taskId.toInt())
  }
}

class HeadlessTaskSupportPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == NativeHeadlessJsTaskSupportSpec.NAME) HeadlessTaskSupportModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      NativeHeadlessJsTaskSupportSpec.NAME to ReactModuleInfo(
        NativeHeadlessJsTaskSupportSpec.NAME,
        HeadlessTaskSupportModule::class.java.name,
        false, // canOverrideExistingModule
        false, // needsEagerInit
        false, // isCxxModule
        true, // isTurboModule
      ),
    )
  }
}
