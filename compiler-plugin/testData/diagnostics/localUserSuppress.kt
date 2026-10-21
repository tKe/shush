// WITH_EXTRA_CHECKERS
// DIAGNOSTICS: +UNUSED_VARIABLE
// SHUSH_RULE: unused=UNUSED_VARIABLE
// SHUSH_ANNOTATED_DECLARATIONS: unused=test.Unused
// Whether a local declaration's @Suppress arguments are resolved when the plugin merges into it differs between
// compilers (not in 2.4, already in 2.5), and with it the FIR dump, though not the diagnostics.
// DISABLE_FIR_DUMP_HANDLER
// FILE: test.kt
package test

annotation class Unused

@Deprecated("old")
fun old(): Int = 1

fun localUserSuppress() {
    @Unused
    @Suppress("DEPRECATION")
    fun localPositional() {
        val x = old()
    }
    localPositional()

    @Unused
    @Suppress(names = ["DEPRECATION"])
    fun localNamed() {
        val x = old()
    }
    localNamed()
}

fun notAnnotatedLocal() {
    @Suppress("DEPRECATION")
    fun localPositional() {
        val <!UNUSED_VARIABLE!>x<!> = old()
    }
    localPositional()
}
