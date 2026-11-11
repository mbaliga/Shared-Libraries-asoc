package sample

import android.util.Log

fun bad() {
    Log.d("x", "android.* in commonMain is banned")
}
