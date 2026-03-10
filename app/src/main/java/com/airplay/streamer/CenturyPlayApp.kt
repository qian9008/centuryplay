package com.airplay.streamer

import android.app.Application
import com.airplay.streamer.util.LogServer
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class CenturyPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Copy libffi.so next to _cffi_backend.so so the linker can find it.
        // Android's linker namespace isolation prevents System.loadLibrary from
        // making symbols visible to Python's dlopen, so the .so must be in the
        // same directory where _cffi_backend.so lives.
        copyLibffiToChaquopy()

        // Initialize Chaquopy Python runtime
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Global crash handler so we always see the stack trace in LogServer
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                LogServer.e("CRASH", "Uncaught exception on ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun copyLibffiToChaquopy() {
        try {
            // Find _cffi_backend.so in Chaquopy's requirements directory
            val chaquopyBase = File(filesDir, "chaquopy/AssetFinder/requirements")
            val cffiBackend = File(chaquopyBase, "_cffi_backend.so")
            val targetLibffi = File(chaquopyBase, "libffi.so")

            // Always overwrite to ensure version matches the APK's cffi build
            if (targetLibffi.exists()) targetLibffi.delete()

            // Extract libffi.so from the APK's native libs
            val nativeLibDir = File(applicationInfo.nativeLibraryDir)
            val sourceLibffi = File(nativeLibDir, "libffi.so")

            if (sourceLibffi.exists()) {
                sourceLibffi.copyTo(targetLibffi, overwrite = true)
                LogServer.d("CenturyPlayApp", "Copied libffi.so to ${targetLibffi.absolutePath}")
            } else {
                // nativeLibraryDir might not have it extracted; extract from APK
                val abi = android.os.Build.SUPPORTED_ABIS[0]
                val entryName = "lib/$abi/libffi.so"
                val apkPath = applicationInfo.sourceDir
                java.util.zip.ZipFile(apkPath).use { zip ->
                    val entry = zip.getEntry(entryName)
                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            targetLibffi.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        LogServer.d("CenturyPlayApp", "Extracted libffi.so from APK to ${targetLibffi.absolutePath}")
                    } else {
                        LogServer.e("CenturyPlayApp", "libffi.so not found in APK at $entryName")
                    }
                }
            }
        } catch (e: Exception) {
            LogServer.e("CenturyPlayApp", "Failed to copy libffi.so: ${e.message}", e)
        }
    }
}
