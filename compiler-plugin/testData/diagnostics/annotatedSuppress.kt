// WITH_EXTRA_CHECKERS
// DIAGNOSTICS: +UNUSED_VARIABLE
// SHUSH_RULE: suppressed=UNUSED_VARIABLE
// SHUSH_ANNOTATED_DECLARATIONS: suppressed=kotlin.Suppress
// FILE: test.kt
package test

// A rule keyed on @Suppress itself: ours sits beside the user's, which is also @Suppress, without repeating it.

@Deprecated("old")
fun old(): Int = 1

@Suppress("DEPRECATION")
fun positional() {
    val x = old()
}

@Suppress(names = ["DEPRECATION"])
fun named() {
    val x = old()
}

@kotlin.Suppress("DEPRECATION")
fun qualified() {
    val x = old()
}

@Suppress("UNUSED_VARIABLE")
fun alreadyIncluded() {
    val x = <!DEPRECATION!>old<!>()
}

@Suppress
fun noNames() {
    val x = <!DEPRECATION!>old<!>()
}

fun notSuppressed() {
    val <!UNUSED_VARIABLE!>x<!> = <!DEPRECATION!>old<!>()
}

@Suppress("DEPRECATION")
class SuppressedClass {
    fun member() {
        val x = old()
    }
}

fun localDeclarations() {
    @Suppress("DEPRECATION")
    fun local() {
        val x = old()
    }
    local()

    fun notSuppressedLocal() {
        val <!UNUSED_VARIABLE!>x<!> = <!DEPRECATION!>old<!>()
    }
    notSuppressedLocal()
}
