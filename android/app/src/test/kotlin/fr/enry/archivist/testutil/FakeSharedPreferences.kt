package fr.enry.archivist.testutil

import android.content.SharedPreferences

/**
 * Minimal in-memory [SharedPreferences] — stands in for `EncryptedSharedPreferences`
 * in tests, which needs a real Android Keystore and can't run here. Only implements
 * what [fr.enry.archivist.data.local.TokenStore] actually calls: `getString`,
 * `edit().putString()/.remove()/.apply()`.
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, String>()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] ?: defValue

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = throw UnsupportedOperationException("not used by TokenStore")

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = throw UnsupportedOperationException("not used by TokenStore")

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = throw UnsupportedOperationException("not used by TokenStore")

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = throw UnsupportedOperationException("not used by TokenStore")

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = throw UnsupportedOperationException("not used by TokenStore")

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pendingPuts = mutableMapOf<String, String>()
        private val pendingRemoves = mutableSetOf<String>()
        private var pendingClear = false

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor {
            if (value == null) pendingRemoves.add(key) else pendingPuts[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pendingRemoves.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            pendingClear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (pendingClear) values.clear()
            pendingRemoves.forEach { values.remove(it) }
            values.putAll(pendingPuts)
        }

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor = throw UnsupportedOperationException("not used by TokenStore")

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor = throw UnsupportedOperationException("not used by TokenStore")

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor = throw UnsupportedOperationException("not used by TokenStore")

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor = throw UnsupportedOperationException("not used by TokenStore")

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = throw UnsupportedOperationException("not used by TokenStore")
    }
}
