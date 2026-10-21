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

// The synthetic @Suppress covers the template lambda's whole body (range-based), including ordinary lambdas and
// local functions nested in it.
fun nestedLambda(a: A) {
    verify { repeat(1) { a.f() } }
    verify {
        repeat(2) {
            a.f()
            a.g(it)
        }
    }
    every {
        listOf(1).forEach { a.g(it) }
        a.f()
    } returns 1
    verify {
        fun local() {
            a.f()
        }
        local()
    }
}

fun nestedLambdaOutsideTemplate(a: A) {
    repeat(1) { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
}
