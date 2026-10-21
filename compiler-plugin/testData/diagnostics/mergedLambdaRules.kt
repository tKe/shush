// WITH_EXTRA_CHECKERS
// DIAGNOSTICS: +UNUSED_VARIABLE
// RETURN_VALUE_CHECKER_MODE: full
// SHUSH_RULE: returnValues=RETURN_VALUE_NOT_USED returnValues=RETURN_VALUE_NOT_USED_COERCION
// SHUSH_CALLS: returnValues=test.template
// SHUSH_RULE: unused=UNUSED_VARIABLE
// SHUSH_ANNOTATED_FUNCTIONS: unused=test.Unused
// SHUSH_RULE: deprecated=DEPRECATION
// SHUSH_ANNOTATED_PARAMETERS: deprecated=test.Legacy
// FILE: test.kt
package test

annotation class Unused

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Legacy

@Deprecated("old")
fun old(): Int = 1

fun f(): Int = 1

// Each rule targets the same lambda in a different way: their diagnostics are merged into one @Suppress.
@Unused
fun template(@Legacy block: () -> Unit) {}

fun plain(block: () -> Unit) {}

fun merged() {
    template {
        val x = old()
        f()
        f()
    }
    template @Suppress("UNUSED_VARIABLE") {
        val x = old()
        f()
    }
}

fun notMerged() {
    plain {
        val <!UNUSED_VARIABLE!>x<!> = <!DEPRECATION!>old<!>()
        <!RETURN_VALUE_NOT_USED_COERCION!>f<!>()
    }
}
