package vn.vhn.pckeyboard.root

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringWriter

class RootCompat {
    companion object {
        fun isCompatibleRooted(): Boolean {
            val (exit, result) = rootRun("sh -c \"echo vhk''ey'boa'rdroottest''e'r'\"",
                true)
            Log.d("VHVH", "Root check result ${result.length}: '" + result + "' exit $exit")
            return result.contains("vhkeyboardroottester");
        }

        fun rootRun(unsafeRawCommand: String, returnOutput: Boolean): Pair<Int, String> {
            try {
                val keyCommand = "su -c $unsafeRawCommand 2>&1"
                val runtime = Runtime.getRuntime()
                val proc = runtime.exec(keyCommand)
                if (returnOutput) {
                    val r = getStringFromInputStream(proc.inputStream).trim { it <= ' ' }
                    while (true) {
                        try {
                            proc.exitValue()
                            break
                        } catch (e: Exception) {
                        }
                    }
                    return Pair(proc.exitValue(), r)
                }
            } catch (e: IOException) {
                Log.e("RootCompat", "Exception running $unsafeRawCommand", e)
            } catch (e: Exception) {
                Log.e("RootCompat", "GException running $unsafeRawCommand", e)
            }
            return Pair(0, "")
        }

        @Throws(IOException::class)
        private fun getStringFromInputStream(stream: InputStream): String {
            var n = 0
            val buffer = CharArray(1024 * 4)
            val reader = InputStreamReader(stream, "UTF8")
            val writer = StringWriter()
            while (true) {
                n = reader.read(buffer)
                if (n == -1) break
                writer.write(buffer, 0, n)
            }
            return writer.toString()
        }
    }
}