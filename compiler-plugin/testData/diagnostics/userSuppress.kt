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
}

// A user-written @Suppress on the lambda must not clash with the plugin's synthetic one (no REPEATED_ANNOTATION).
fun userSuppress(a: A) {
    verify @Suppress("RETURN_VALUE_NOT_USED_COERCION") { a.f() }
    verify(block = @Suppress("RETURN_VALUE_NOT_USED", "RETURN_VALUE_NOT_USED_COERCION") { a.f(); a.f() })
    every @Suppress("RETURN_VALUE_NOT_USED") { a.f(); a.f() } returns 1
    verify { @Suppress("RETURN_VALUE_NOT_USED") a.f() }
}
