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

// FILE: suite.kt
package dev.mokkery

import dev.mokkery.internal.annotations.Templating

// Not one of the configured function names: only selected via the @Templating type annotation.
class MokkerySuiteScope {
    fun verify(block: @Templating MokkeryTemplatingScope.() -> Unit) {}
    fun <T> stub(block: @Templating MokkeryTemplatingScope.() -> T): AnsweringScope<T> = TODO()
}

// FILE: other.kt
package other

import dev.mokkery.MokkeryTemplatingScope
import dev.mokkery.internal.annotations.Templating

fun verify(block: @Templating MokkeryTemplatingScope.() -> Unit) {}
fun verifyPlain(block: MokkeryTemplatingScope.() -> Unit) {}

// FILE: test.kt
package test

import dev.mokkery.MokkerySuiteScope

class A {
    fun f(): Int = 1
    fun g(x: Int): String = x.toString()
}

fun suiteScope(scope: MokkerySuiteScope, a: A) {
    scope.verify { a.f() }
    with(scope) {
        verify {
            a.f()
            a.g(1)
        }
    }
    scope.stub { a.g(1); a.f() } returns 1
}

fun otherPackage(a: A) {
    other.verify { a.f(); a.g(1) }
    other.verifyPlain { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
}
