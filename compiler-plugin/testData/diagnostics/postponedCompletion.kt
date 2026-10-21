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

// Calls whose completion is postponed until the enclosing call is completed.
fun lastStatementOfEnclosingLambda(a: A) {
    run { verify { a.f() } }
    run {
        a.<!RETURN_VALUE_NOT_USED!>f<!>()
        verify { a.f(); a.g(1) }
    }
    with(a) { verify { f() } }
}

fun insideMap(a: A): List<Unit> =
    listOf(1).map { verify { a.g(it) } }

fun insideMapMultiStatement(a: A): List<Unit> =
    listOf(1).map { verify { a.f(); a.g(it) } }

fun everyLastStatement(a: A): AnsweringScope<Int> =
    run { every { a.g(1); a.f() } }

fun insideSuspendLambda(a: A) {
    val block: suspend () -> Unit = { verifySuspend { a.f(); a.g(1) } }
    val other: suspend () -> Unit = { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
}

suspend fun insideSuspendFunction(a: A) {
    verifySuspend { a.f() }
    a.<!RETURN_VALUE_NOT_USED!>f<!>()
}
