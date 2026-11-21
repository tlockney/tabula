package net.lockney.tabula

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] for JVM unit tests, which records whether an
 * edit reached storage through [commit][SharedPreferences.Editor.commit] or
 * was merely queued with [apply][SharedPreferences.Editor.apply] - the
 * pairing write path must use commit (PR review item 2).
 */
class FakeSharedPreferences(
    /** When set, [commit][SharedPreferences.Editor.commit] reports failure. */
    var failCommit: Boolean = false,
) : SharedPreferences {
    /** Keys whose last edit was committed (synchronously persisted). */
    val committed = mutableSetOf<String>()

    /** Keys whose last edit was only applied (async, possibly never flushed). */
    val applied = mutableSetOf<String>()

    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): Map<String, Any?> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        values[key] as Set<String>? ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearFirst = false

        private fun self(): SharedPreferences.Editor = this

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return self()
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = values
            return self()
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return self()
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return self()
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return self()
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return self()
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = REMOVED
            return self()
        }

        override fun clear(): SharedPreferences.Editor {
            clearFirst = true
            return self()
        }

        override fun commit(): Boolean {
            // a failed commit persists nothing, like the real SharedPreferences
            if (failCommit) return false
            flush()
            committed += pending.keys
            return true
        }

        override fun apply() {
            flush()
            applied += pending.keys
        }

        private fun flush() {
            if (clearFirst) values.clear()
            for ((key, value) in pending) {
                if (value === REMOVED) values.remove(key) else values[key] = value
            }
        }
    }

    private companion object {
        val REMOVED = Any()
    }
}
