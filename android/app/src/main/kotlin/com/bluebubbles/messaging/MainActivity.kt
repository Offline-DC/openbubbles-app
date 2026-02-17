package com.bluebubbles.messaging

import android.util.Log
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import com.bluebubbles.messaging.services.backend_ui_interop.MethodCallHandler
import com.bluebubbles.messaging.services.foreground.ForegroundServiceBroadcastReceiver
import com.bluebubbles.messaging.Constants
import com.bluebubbles.messaging.services.extension.KeyboardViewFactory
import com.bluebubbles.messaging.services.extension.LiveExtensionFactory
import com.bluebubbles.messaging.services.extension.MessageViewHandle
import com.bluebubbles.messaging.services.rustpush.APNService
import com.bluebubbles.messaging.services.system.CreateDocumentHandler
import com.bluebubbles.messaging.services.system.EnableBTHandler
import com.rmawatson.flutterisolate.FlutterIsolatePlugin
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : FlutterFragmentActivity(), ComponentCallbacks2 {
    private val mouseOn = AtomicBoolean(false)
    @Volatile private var rootOk: Boolean? = null

    private fun checkRootOnce(): Boolean {
        rootOk?.let { return it }
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val exit = p.waitFor()
                val ok = (exit == 0)
                rootOk = ok
                Log.d("DumbMouse", "root check exit=$exit ok=$ok")
                ok
            } catch (t: Throwable) {
                rootOk = false
                Log.e("DumbMouse", "root check failed", t)
                false
            }
        }

        private fun runAsRootAsync(cmd: String) {
            Thread {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))

                    val out = BufferedReader(InputStreamReader(p.inputStream)).readText().trim()
                    val err = BufferedReader(InputStreamReader(p.errorStream)).readText().trim()

                    val exit = p.waitFor()
                    Log.d("DumbMouse", "cmd=$cmd exit=$exit out=$out err=$err")
                } catch (t: Throwable) {
                    Log.e("DumbMouse", "failed cmd=$cmd", t)
                }
            }.start()
        }

        private fun mouseEnable() {
            if (!checkRootOnce()) return
            if (mouseOn.compareAndSet(false, true)) {
                runAsRootAsync("/data/adb/modules/DumbMouse/mouse enable")
            } else {
                Log.d("DumbMouse", "enable skipped (already on)")
            }
        }

        private fun mouseDisable() {
            if (!checkRootOnce()) return
            if (mouseOn.compareAndSet(true, false)) {
                runAsRootAsync("/data/adb/modules/DumbMouse/mouse disable")
            } else {
                Log.d("DumbMouse", "disable skipped (already off)")
            }
        }

        override fun onStart() {
            super.onStart()
            mouseEnable()
        }

        override fun onStop() {
            mouseDisable()
            super.onStop()
        }

    companion object {
        var engine: FlutterEngine? = null
        var engine_ready = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, APNService::class.java))
        } else {
            startService(Intent(this, APNService::class.java))
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        engine_ready = false
        engine = flutterEngine
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, Constants.methodChannel).setMethodCallHandler { call, result ->
            if (call.method == "engine-done") {
                Log.i("BBEngine", "Destroyed");
                // this must be here in case another engine has been spawned in the meantime
                flutterEngine.destroy()
                if (engine == flutterEngine)
                    engine = null
            }
            MethodCallHandler().methodCallHandler(call, result, this)
        }
        flutterEngine.platformViewsController.registry.registerViewFactory("extension-keyboard", KeyboardViewFactory())
        flutterEngine.platformViewsController.registry.registerViewFactory("extension-live", LiveExtensionFactory())
    }

    override fun onDestroy() {
        Log.d(Constants.logTag, "BlueBubbles MainActivity is being destroyed")
        engine = null

        // If we are finishing "gracefully", the dart code would have started the foreground service.
        // If we are finishing because the system is destroying the activity, we need to start the foreground service
        // via a broadcast intent.
        if (isFinishing) {
            Log.d(Constants.logTag, "BlueBubbles activity is finishing")
        } else {
            Log.d(Constants.logTag, "BlueBubbles activity is being destroyed by the system")

            val prefs = applicationContext.getSharedPreferences("FlutterSharedPreferences", 0)
            val keepAppAlive: Boolean = prefs.getBoolean("flutter.keepAppAlive", false)

            // Create an intent to start the foreground service
            if (keepAppAlive) {
                Log.d(Constants.logTag, "Creating broadcast intent to restart the foreground service...")
                val broadcastIntent = Intent(this, ForegroundServiceBroadcastReceiver::class.java)
                broadcastIntent.setAction("restartservice");
                sendBroadcast(broadcastIntent);
            }
        }

        try {
            super.onDestroy()
        } catch (e: ConcurrentModificationException) {
            Log.d(Constants.logTag, "Caught ConcurrentModificationException when destroying MainActivity")
            Log.e(Constants.logTag, e.stackTraceToString())
        } catch (e: Exception) {
            Log.d(Constants.logTag, "Caught unhandled Exception when destroying MainActivity")
            Log.e(Constants.logTag, e.stackTraceToString())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Constants.notificationListenerRequestCode) {
            MethodCallHandler.getNotificationListenerResult?.success(resultCode == Activity.RESULT_OK)
        }
        if (requestCode == Constants.documentSaveRequestCode) {
            var result = CreateDocumentHandler.savedResult!!
            CreateDocumentHandler.savedResult = null
            val uri = data?.data
            if (uri == null) {
                result.success(null)
                return
            }

            try {
                val output = contentResolver.openOutputStream(uri)!!
                val input = FileInputStream(CreateDocumentHandler.savedPath!!)
                input.copyTo(output)
                output.close()
                input.close()
                result.success(null)
            } catch (e: Exception) {
                result.error("FILE_WRITE_ERROR", e.message, null)
            }
        }
        if (requestCode == Constants.enableBtRequestCode) {
            var result = EnableBTHandler.savedResult!!
            result.success(true)
        }
    }
}