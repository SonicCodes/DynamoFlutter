package io.flutter.embedding.engine.loader;

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import io.flutter.BuildConfig
import io.flutter.Log
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.util.PathUtils
import io.flutter.view.VsyncWaiter
import java.io.File
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.collections.ArrayList

class CsFlutterLoader(val flutterJNI: FlutterJNI, val libName: String) : FlutterLoader(flutterJNI) {
    var initialized = false;
    private val TAG = "FlutterLoader"
    private var flutterApplicationInfo: FlutterApplicationInfo? = null
    private val OLD_GEN_HEAP_SIZE_META_DATA_KEY = "io.flutter.embedding.android.OldGenHeapSize"
    private val ENABLE_SKPARAGRAPH_META_DATA_KEY = "io.flutter.embedding.android.EnableSkParagraph"
    private var settings: Settings? = null
    // Must match values in flutter::switches
    val AOT_SHARED_LIBRARY_NAME = "aot-shared-library-name"
    val SNAPSHOT_ASSET_PATH_KEY = "snapshot-asset-path"
    val VM_SNAPSHOT_DATA_KEY = "vm-snapshot-data"
    val ISOLATE_SNAPSHOT_DATA_KEY = "isolate-snapshot-data"
    val FLUTTER_ASSETS_DIR_KEY = "flutter-assets-dir"

    // Resource names used for components of the precompiled snapshot.
    private val DEFAULT_LIBRARY = "libflutter.so"
    private val DEFAULT_KERNEL_BLOB = "kernel_blob.bin"
    private var initStartTimestampMillis: Long = 0

    inner class InitResult constructor(
        val appStoragePath: String,
        val engineCachesPath: String,
        val dataDirPath: String
    )


    class Settings {
        /**
         * Set the tag associated with Flutter app log messages.
         *
         * @param tag Log tag.
         */
        var logTag: String? = null
    }

    fun fap(filePath: String): String {
        return flutterApplicationInfo?.flutterAssetsDir + File.separator + filePath
    }

    override fun findAppBundlePath(): String {
        return flutterApplicationInfo!!.flutterAssetsDir
    }

    var initResultFutureT: Future<InitResult>? = null
    /**
     * Blocks until initialization of the native system has completed.
     *
     *
     * Calling this method multiple times has no effect.
     *
     * @param applicationContext The Android application context.
     * @param args Flags sent to the Flutter runtime.
     */
    override fun ensureInitializationComplete(
        applicationContext: Context, args: Array<String?>?
    ) {
        if (initialized) {
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException(
                "ensureInitializationComplete must be called on the main thread"
            )
        }
        try {
            val result = initResultFutureT!!.get()
            val shellArgs: MutableList<String> = ArrayList()
            shellArgs.add("--icu-symbol-prefix=_binary_icudtl_dat")
            shellArgs.add(
                "--icu-native-lib-path="
                        + flutterApplicationInfo?.nativeLibraryDir
                        + File.separator
                        + DEFAULT_LIBRARY
            )
            if (args != null) {
                Collections.addAll<String>(shellArgs, *args)
            }
            var kernelPath: String? = null
            shellArgs.add(
                ("--"
                        + AOT_SHARED_LIBRARY_NAME
                        + "="
                        + libName)
            )
            shellArgs.add("--cache-dir-path=" + result.engineCachesPath)
            if (flutterApplicationInfo?.clearTextPermitted == false) {
                shellArgs.add("--disallow-insecure-connections")
            }
            if (flutterApplicationInfo?.domainNetworkPolicy != null) {
                shellArgs.add("--domain-network-policy=" + flutterApplicationInfo!!.domainNetworkPolicy)
            }
            if (settings?.logTag != null) {
                shellArgs.add("--log-tag=" + settings!!.logTag)
            }
            val applicationInfo = applicationContext
                .packageManager
                .getApplicationInfo(
                    applicationContext.packageName, PackageManager.GET_META_DATA
                )
            val metaData = applicationInfo.metaData
            var oldGenHeapSizeMegaBytes =
                metaData?.getInt(OLD_GEN_HEAP_SIZE_META_DATA_KEY) ?: 0
            if (oldGenHeapSizeMegaBytes == 0) {
                // default to half of total memory.
                val activityManager =
                    applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                oldGenHeapSizeMegaBytes = (memInfo.totalMem / 1e6 / 2).toInt()
            }
            shellArgs.add("--old-gen-heap-size=$oldGenHeapSizeMegaBytes")
            if (metaData != null && metaData.getBoolean(ENABLE_SKPARAGRAPH_META_DATA_KEY)) {
                shellArgs.add("--enable-skparagraph")
            }
            val initTimeMillis = SystemClock.uptimeMillis() - initStartTimestampMillis
            flutterJNI.init(
                applicationContext,
                shellArgs.toTypedArray(),
                kernelPath,
                result.appStoragePath,
                result.engineCachesPath,
                initTimeMillis
            )
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Flutter initialization failed.", e)
            throw RuntimeException(e)
        }
    }


    override fun startInitialization(applicationContext: Context) {
        // Do not run startInitialization more than once.
        if (this.settings != null) {
            return
        }
        check(Looper.myLooper() == Looper.getMainLooper()) { "startInitialization must be called on the main thread" }

        // Ensure that the context is actually the application context.
        val appContext = applicationContext.applicationContext
        this.settings = settings
        initStartTimestampMillis = SystemClock.uptimeMillis()
        flutterApplicationInfo = ApplicationInfoLoader.load(appContext)
        VsyncWaiter.getInstance((appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager))
            .init()

        // Use a background thread for initialization tasks that require disk access.
        val initTask = Callable<InitResult> {
            val resourceExtractor = initResources(appContext)
            flutterJNI.loadLibrary()

            // Prefetch the default font manager as soon as possible on a background thread.
            // It helps to reduce time cost of engine setup that blocks the platform thread.
            Executors.newSingleThreadExecutor()
                .execute { flutterJNI.prefetchDefaultFontManager() }
            resourceExtractor?.waitForCompletion()
            InitResult(
                PathUtils.getFilesDir(appContext),
                PathUtils.getCacheDirectory(appContext),
                PathUtils.getDataDirectory(appContext)
            )
        }
        this.initResultFutureT = Executors.newSingleThreadExecutor().submit(initTask)
    }

    /** Extract assets out of the APK that need to be cached as uncompressed files on disk.  */
    private fun initResources(applicationContext: Context): ResourceExtractor? {
        var resourceExtractor: ResourceExtractor? = null
        if (BuildConfig.DEBUG || BuildConfig.JIT_RELEASE) {
            val dataDirPath = PathUtils.getDataDirectory(applicationContext)
            val packageName = applicationContext.packageName
            val packageManager = applicationContext.packageManager
            val assetManager = applicationContext.resources.assets
            resourceExtractor =
                ResourceExtractor(dataDirPath, packageName, packageManager, assetManager)

            // In debug/JIT mode these assets will be written to disk and then
            // mapped into memory so they can be provided to the Dart VM.
            flutterApplicationInfo?.let {
                resourceExtractor
                    .addResource(fap(it.vmSnapshotData))
                    .addResource(fap(it.isolateSnapshotData))
                    .addResource(fap(DEFAULT_KERNEL_BLOB))
            }

            resourceExtractor.start()
        }
        return resourceExtractor
    }


}