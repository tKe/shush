// RETURN_VALUE_CHECKER_MODE: full
// SHUSH_PRESET: mokkery
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

fun singleStatement(a: A) {
    verify { a.f() }
    verifySuspend { a.f() }
    verify(mode = null) { a.g(1) }
}

fun multiStatement(a: A) {
    verify {
        a.f()
        a.g(1)
    }
    verifySuspend {
        a.f()
        a.g(2)
    }
    verify { a.f(); a.g(3); a.f() }
}

fun outsideTemplate(a: A) {
    a.<!RETURN_VALUE_NOT_USED!>f<!>()
    verify { a.f() }
    a.<!RETURN_VALUE_NOT_USED!>g<!>(1)
    val ok: () -> Unit = { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
}
