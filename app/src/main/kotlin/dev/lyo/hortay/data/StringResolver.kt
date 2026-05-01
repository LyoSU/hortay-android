package dev.lyo.hortay.data

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Thin abstraction over Android string resources for the data layer. Lets unit tests
 * substitute a stub without instantiating [Resources] (which requires AssetManager +
 * DisplayMetrics + Configuration plumbing not available on a JVM-only test classpath).
 *
 * Production wires [Resources.toStringResolver]; tests use a fake that just returns
 * placeholder strings so the data layer's `getString` calls don't crash.
 */
interface StringResolver {
    fun getString(@StringRes id: Int): String
    fun getString(@StringRes id: Int, vararg args: Any?): String
    fun getQuantityString(@PluralsRes id: Int, count: Int, vararg args: Any?): String
}

private class ResourcesStringResolver(private val res: Resources) : StringResolver {
    override fun getString(id: Int): String = res.getString(id)
    override fun getString(id: Int, vararg args: Any?): String = res.getString(id, *args)
    override fun getQuantityString(id: Int, count: Int, vararg args: Any?): String =
        res.getQuantityString(id, count, *args)
}

fun Resources.toStringResolver(): StringResolver = ResourcesStringResolver(this)
