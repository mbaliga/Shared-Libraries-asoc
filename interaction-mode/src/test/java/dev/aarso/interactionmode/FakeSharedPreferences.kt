package dev.aarso.interactionmode

import android.content.SharedPreferences

/**
 * A hand-rolled [SharedPreferences] test double — not Robolectric, not a mock framework.
 * [SharedPreferences] and [SharedPreferences.Editor] are plain interfaces with no method
 * bodies to stub out, so implementing them directly here compiles and runs on a normal JVM
 * unit test: nothing ever calls into real Android framework code.
 *
 * Only the members [PrefsInteractionModeStore] actually uses ([SharedPreferences.getString],
 * [SharedPreferences.contains], [SharedPreferences.edit], [Editor.putString],
 * [Editor.apply]/[Editor.commit]) do real work. Everything else throws, so a future change that
 * starts relying on an unimplemented member fails loudly in a test instead of silently returning
 * a wrong default.
 */
internal class FakeSharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, String?>()

    override fun getString(key: String, defValue: String?): String? =
        if (values.containsKey(key)) values[key] else defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getInt(key: String, defValue: Int): Int = unsupported()
    override fun getLong(key: String, defValue: Long): Long = unsupported()
    override fun getFloat(key: String, defValue: Float): Float = unsupported()
    override fun getBoolean(key: String, defValue: Boolean): Boolean = unsupported()
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = unsupported()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ): Unit = unsupported()

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ): Unit = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("FakeSharedPreferences: not needed by PrefsInteractionModeStore")

    private inner class FakeEditor : SharedPreferences.Editor {
        private val putString = mutableMapOf<String, String?>()
        private val removedKeys = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            putString[key] = value
            removedKeys.remove(key)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removedKeys.add(key)
            putString.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removedKeys.forEach { values.remove(it) }
            values.putAll(putString)
            return true
        }

        override fun apply() {
            commit()
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = unsupported()
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = unsupported()
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = unsupported()
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = unsupported()
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = unsupported()
    }
}
