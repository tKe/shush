// SHUSH_RULE: templates=RETURN_VALUE_NOT_USED templates=RETURN_VALUE_NOT_USED_COERCION
// SHUSH_CALLS: templates=test.myTemplate
// SHUSH_ANNOTATED_PARAMETERS: templates=test.Recorded
// SHUSH_PRESET: mokkery
// FILE: templating.kt
package dev.mokkery.internal.annotations

@Target(AnnotationTarget.TYPE)
annotation class Templating

// FILE: mokkery.kt
package dev.mokkery

import dev.mokkery.internal.annotations.Templating
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

object MokkeryTemplatingScope

class AnsweringScope<T>(val recorded: T) {
    var answer: T? = null
    infix fun returns(value: T) {
        answer = value
    }
}

fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

fun <T> every(block: @Templating MokkeryTemplatingScope.() -> T): AnsweringScope<T> =
    AnsweringScope(MokkeryTemplatingScope.block())

fun <T> everySuspend(block: suspend @Templating MokkeryTemplatingScope.() -> T): AnsweringScope<T> =
    AnsweringScope(runSuspend { MokkeryTemplatingScope.block() })

fun verify(mode: Any? = null, block: @Templating MokkeryTemplatingScope.() -> Unit) {
    MokkeryTemplatingScope.block()
}

fun verifySuspend(mode: Any? = null, block: suspend @Templating MokkeryTemplatingScope.() -> Unit) {
    runSuspend { MokkeryTemplatingScope.block() }
}

// FILE: box.kt
package test

import dev.mokkery.*

annotation class Recorded

class A {
    val calls = mutableListOf<String>()
    fun f(): Int {
        calls += "f"
        return 1
    }
    fun g(x: Int): String {
        calls += "g$x"
        return x.toString()
    }
    suspend fun s(): Int {
        calls += "s"
        return 2
    }
}

fun myTemplate(block: () -> Unit) = block()
fun record(@Recorded block: () -> Unit) = block()

fun box(): String {
    val a = A()
    verify { a.f() }
    verify(mode = "any") {
        a.f()
        a.g(1)
    }
    verifySuspend {
        a.s()
        a.g(2)
    }
    val scope = every {
        a.g(3)
        a.f()
    }
    scope returns 5
    val suspendScope = everySuspend {
        a.f()
        a.s()
    }
    suspendScope returns 6
    verify @Suppress("RETURN_VALUE_NOT_USED_COERCION") { a.f() }
    verify { repeat(2) { a.g(it) } }
    run { verify { a.f() } }
    val mapped = listOf(4).map { verify { a.g(it) } }
    myTemplate { a.f(); a.g(5) }
    record { a.g(6); a.f() }

    val expected = listOf(
        "f",
        "f", "g1",
        "s", "g2",
        "g3", "f",
        "f", "s",
        "f",
        "g0", "g1",
        "f",
        "g4",
        "f", "g5",
        "g6", "f",
    )
    if (a.calls != expected) return "Fail calls: ${a.calls}"
    if (mapped.size != 1 || scope.recorded != 1 || scope.answer != 5) return "Fail every: ${scope.recorded} ${scope.answer}"
    if (suspendScope.recorded != 2 || suspendScope.answer != 6) return "Fail everySuspend: ${suspendScope.recorded} ${suspendScope.answer}"
    return "OK"
}
