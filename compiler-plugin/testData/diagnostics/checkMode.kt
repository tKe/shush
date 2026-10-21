// RETURN_VALUE_CHECKER_MODE: check
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
@file:MustUseReturnValues

package test

import dev.mokkery.*

class A {
    fun f(): Int = 1
    fun g(x: Int): String = x.toString()
}

// `-Xreturn-value-checker=check`: only @MustUseReturnValues declarations (and the stdlib) are checked.
fun checkMode(a: A) {
    verify { a.f(); a.g(1) }
    every { a.g(1); listOf(1).first() } returns 1
    a.<!RETURN_VALUE_NOT_USED!>f<!>()
    <!RETURN_VALUE_NOT_USED!>listOf<!>(1)
}
