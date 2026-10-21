// RETURN_VALUE_CHECKER_MODE: full
// SHUSH_RULE: templates=RETURN_VALUE_NOT_USED templates=RETURN_VALUE_NOT_USED_COERCION
// SHUSH_ANNOTATED_PARAMETERS: templates=test.Recorded
// SHUSH_ANNOTATED_PARAMETERS: templates=test.RecordedType
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

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Recorded

@Target(AnnotationTarget.TYPE)
annotation class RecordedType

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE)
annotation class NotConfigured

fun record(@Recorded block: () -> Unit) {}
fun recordType(block: @RecordedType () -> Unit) {}
fun <T> recordTypeWithReceiver(block: @RecordedType A.() -> T): T = TODO()
fun recordAll(@Recorded vararg blocks: () -> Unit) {}
fun recordAllTyped(vararg blocks: @RecordedType () -> Unit) {}
fun notRecorded(@NotConfigured block: @NotConfigured () -> Unit) {}

fun valueParameterAnnotation(a: A) {
    record { a.f() }
    record {
        a.f()
        a.g(1)
    }
}

fun typeAnnotation(a: A) {
    recordType { a.f() }
    recordType { a.f(); a.g(1) }
    val r: Int = recordTypeWithReceiver { g(1); f() }
}

fun varargs(a: A) {
    recordAll({ a.f() }, { a.g(1); a.f() })
    recordAllTyped({ a.f() }, { a.g(1); a.f() })
}

fun notAnnotated(a: A) {
    notRecorded { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
}
