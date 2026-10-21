// RETURN_VALUE_CHECKER_MODE: full
// FILE: templating.kt
package dev.mokkery.internal.annotations

@Target(AnnotationTarget.TYPE)
annotation class Templating

// FILE: mokkery.kt
package dev.mokkery

import dev.mokkery.internal.annotations.Templating

interface MokkeryTemplatingScope

class AnsweringScope<T> {
    infix fun returns(value: T) {}
}

fun <T> every(block: @Templating MokkeryTemplatingScope.() -> T): AnsweringScope<T> = TODO()
fun <T> everySuspend(block: suspend @Templating MokkeryTemplatingScope.() -> T): AnsweringScope<T> = TODO()
fun verify(mode: Any? = null, block: @Templating MokkeryTemplatingScope.() -> Unit) {}
fun verifySuspend(mode: Any? = null, block: suspend @Templating MokkeryTemplatingScope.() -> Unit) {}

// FILE: test.kt
package test

import dev.mokkery.*

class A {
    fun f(): Int = 1
    fun g(x: Int): String = x.toString()
}

// Without the Mokkery defaults (and nothing else configured) the plugin does nothing.
fun mokkeryNotSelected(a: A) {
    verify { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
    verify(mode = null) {
        // Non-last statements of Unit-returning lambdas are reported as RETURN_VALUE_NOT_USED_COERCION by Kotlin 2.4
        // and as RETURN_VALUE_NOT_USED by 2.5, so only the last statement is checked here.
        a.<!RETURN_VALUE_NOT_USED_COERCION!>g<!>(1)
    }
    verifySuspend { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
    every {
        a.<!RETURN_VALUE_NOT_USED!>g<!>(1)
        a.f()
    } returns 1
}
