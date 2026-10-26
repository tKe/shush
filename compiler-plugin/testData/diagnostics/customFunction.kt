// RETURN_VALUE_CHECKER_MODE: full
// SHUSH_RULE: templates=RETURN_VALUE_NOT_USED templates=RETURN_VALUE_NOT_USED_COERCION
// SHUSH_CALL_LAMBDAS: templates=test.myTemplate
// SHUSH_CALL_LAMBDAS: templates=test.Recorder.record templates=test.recordAll
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

fun myTemplate(block: () -> Unit) {}
fun recordAll(vararg blocks: () -> Unit) {}
fun notATemplate(block: () -> Unit) {}

class Recorder {
    fun record(block: A.() -> Unit) {}
}

fun customFunctions(a: A, recorder: Recorder) {
    myTemplate { a.f() }
    myTemplate {
        a.f()
        a.g(1)
    }
    recorder.record { f(); g(2) }
    recordAll({ a.f() }, { a.g(1) })
    notATemplate { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
}

// Mokkery defaults are still active.
fun mokkeryStillWorks(a: A) {
    verify { a.f() }
}
