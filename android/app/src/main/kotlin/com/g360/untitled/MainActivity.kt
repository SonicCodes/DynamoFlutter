package com.g360.untitled

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.embedding.engine.loader.ApplicationInfoLoader
import io.flutter.embedding.engine.loader.FlutterLoader
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okio.*
import java.time.Duration

class ProgressResponseBody(val responseBody: ResponseBody,
                           val downloadProgressFun: (bytesRead: Long, contentLength: Long, isDone: Boolean) -> Unit) : ResponseBody() {

    lateinit var bufferedSource: BufferedSource

    override fun contentLength(): Long = responseBody.contentLength()

    override fun contentType(): MediaType? = responseBody.contentType()

    override fun source(): BufferedSource {
        if (!::bufferedSource.isInitialized) {
            bufferedSource = source(responseBody.source()).buffer()
        }
        return bufferedSource
    }

    private fun source(source: Source): Source {
        return object : ForwardingSource(source) {
            var totalBytesRead: Long = 0
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read: Long = super.read(sink, byteCount)
                totalBytesRead += if (read != -1L) read else 0
                downloadProgressFun(totalBytesRead, responseBody.contentLength(), read == -1L)
                return read
            }
        }
    }
}

@Throws(IOException::class)
fun copy(src: File?, dst: File?) {
    FileInputStream(src).use { `in` ->
        FileOutputStream(dst).use { out ->
            // Transfer bytes from in to out
            val buf = ByteArray(1024)
            var len: Int
            while (`in`.read(buf).also { len = it } > 0) {
                out.write(buf, 0, len)
            }
        }
    }
}
fun getRandomString(length: Int) : String {
    val allowedChars = ('a'..'z')
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}
suspend fun downloadFile(
    url: String,
    downloadFile: FileOutputStream,
    error: (e: Exception) -> Unit,
    downloadProgressFun: (bytesRead: Long, contentLength: Long, isDone: Boolean) -> Unit) {

    withContext(Dispatchers.IO) {
        val request = with(Request.Builder()) {
            url(url)
        }.build()
        val client = with(OkHttpClient.Builder()) {
            addNetworkInterceptor { chain ->
                val originalResponse = chain.proceed(chain.request())
                val responseBody = originalResponse.body()
                responseBody?.let {
                    originalResponse.newBuilder().body(ProgressResponseBody(it,
                        downloadProgressFun)).build()
                }
            }
        }.build()
        try {
            val execute = client.newCall(request).execute()

            val body = execute.body()
            body?.let {
                with(downloadFile) {
                    write(body.bytes())
                    close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            error(e)
        }
    }
}
class MainActivity: Activity() {
    companion object {
        const val REQUEST_PERMISSION = 1
    }
    fun perm() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_PERMISSION)
        } else {
            dostuff()
        }
    }
    var url = ""

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSION -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                dostuff()
            }
        }
    }

    @SuppressLint("CheckResult")
    fun dostuff(){
        val progress = findViewById<ProgressBar>(R.id.progress)
        val libName = "lib${getRandomString(9)}.so"
            progress.visibility = VISIBLE;
            progress.progress = 0;
            GlobalScope.launch {
                downloadFile(url, openFileOutput(libName, MODE_PRIVATE) , {
                    throw it;
                    progress.visibility = GONE;
                    Toast.makeText(this@MainActivity, "Something went wrong", Toast.LENGTH_SHORT).show()
                }) {br, cl, done ->
                    runOnUiThread {
                        if(done){
                            Log.d("Lib saved", libName)
                            progress.visibility = GONE;
                            val myIntent = Intent(this@MainActivity, MiniAppScreen::class.java)
                            myIntent.putExtra("libName", this@MainActivity.getFileStreamPath(libName).absolutePath)
                            startActivity(myIntent)
                        }else{
                            progress.progress = ((br.toDouble()/cl.toDouble())*100).toInt()
                        }
                    }
                }
            }


    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.button).setOnClickListener {
            url = "https://firebasestorage.googleapis.com/v0/b/api-project-402081121050.appspot.com/o/FlutterMiniappsTest%2Flibhelloworld.so?alt=media&token=560a8a5e-fa35-4310-b352-834278e5f73d"
            perm()
        }
        findViewById<Button>(R.id.button2).setOnClickListener {
            url = "https://firebasestorage.googleapis.com/v0/b/api-project-402081121050.appspot.com/o/FlutterMiniappsTest%2Flibanotherhelloworld.so?alt=media&token=dc7258eb-4c7f-410a-b1b5-3c4974cff558"
            perm()
        }
        findViewById<Button>(R.id.button3).setOnClickListener {
            url = "https://firebasestorage.googleapis.com/v0/b/api-project-402081121050.appspot.com/o/FlutterMiniappsTest%2Flibboring.so?alt=media&token=b22a9eb2-d640-4a30-a123-2f439596eeb9"
            perm()
        }
    }
}
