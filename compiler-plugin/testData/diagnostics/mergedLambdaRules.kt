// WITH_EXTRA_CHECKERS
// DIAGNOSTICS: +UNUSED_VARIABLE
// RETURN_VALUE_CHECKER_MODE: full
// SHUSH_RULE: returnValues=RETURN_VALUE_NOT_USED returnValues=RETURN_VALUE_NOT_USED_COERCION
// SHUSH_CALL_LAMBDAS: returnValues=test.template
// SHUSH_RULE: unused=UNUSED_VARIABLE
// SHUSH_ANNOTATED_CALLS: unused=test.Unused
// SHUSH_RULE: deprecated=DEPRECATION
// SHUSH_ANNOTATED_PARAMETER_LAMBDAS: deprecated=test.Legacy
// FILE: test.kt
package test

annotation class Unused

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Legacy

@Deprecated("old")
fun old(): Int = 1

fun f(): Int = 1

// callLambdas and annotatedParameterLambdas both target the lambda: their diagnostics are merged into one @Suppress.
// annotatedCalls puts its own on the call around it.
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
