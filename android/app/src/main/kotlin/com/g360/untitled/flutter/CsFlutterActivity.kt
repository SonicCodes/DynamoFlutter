package io.flutter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs.BackgroundMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.embedding.engine.FlutterShellArgs
import io.flutter.embedding.engine.loader.CsFlutterLoader
// A number of methods in this class have the same implementation as FlutterFragmentActivity. These
// methods are duplicated for readability purposes. Be sure to replicate any change in this class in
// FlutterFragmentActivity, too.
open class CsFlutterActivity(): FlutterActivity() {
    var flutJNI:FlutterJNI? = null;
    var fltLoader:CsFlutterLoader? = null;
    var fltEngine: FlutterEngine? = null;
    override fun onDestroy() {
        super.onDestroy()
        kotlin.runCatching {
            detachFromFlutterEngine()
        }
        kotlin.runCatching {
            flutJNI?.detachFromNativeAndReleaseResources();
        }
    }

    override fun provideFlutterEngine(context: Context): FlutterEngine? {
        FlutterInjector.reset()
        flutJNI = FlutterJNI();
        val path = intent.getStringExtra("libName")!!
        Toast.makeText(this, path, Toast.LENGTH_LONG).show()
        fltLoader = CsFlutterLoader(flutJNI!!,path)
        FlutterInjector.setInstance(FlutterInjector.Builder().setFlutterLoader(fltLoader!!).setDeferredComponentManager(null).build())
        fltEngine = FlutterEngine(this, fltLoader, flutJNI!!);
        return fltEngine;
    }
}