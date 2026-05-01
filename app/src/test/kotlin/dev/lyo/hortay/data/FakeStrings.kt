package dev.lyo.hortay.data

/**
 * Test stub for [StringResolver]. Returns deterministic placeholder strings without
 * touching Android resources, so JVM-only unit tests can construct repositories that
 * accept a [StringResolver] in their constructor.
 *
 * Tests that assert on user-facing message content should not use this fake — instead,
 * they should pass a per-test stub that returns the expected string for the IDs they
 * care about. The current consumers only check structural state (Error vs Ready), so
 * a single shared fake is enough.
 */
internal object FakeStrings : StringResolver {
    override fun getString(id: Int): String = "str:$id"
    override fun getString(id: Int, vararg args: Any?): String =
        "str:$id(${args.joinToString(",")})"
    override fun getQuantityString(id: Int, count: Int, vararg args: Any?): String =
        "plural:$id($count;${args.joinToString(",")})"
}
