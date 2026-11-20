// DIAGNOSTICS: +RETURN_VALUE_NOT_USED
// MODULE: lib
// FILE: lib.kt
@file:MustUseReturnValues
package lib

class Scope

// Like arrow's `resourceScope`.
suspend fun <A> resourceScope(action: suspend Scope.() -> A): A = Scope().action()

inline fun <R> scope(action: Scope.() -> R): R = Scope().action()

inline fun <R> choose(flag: Boolean, a: () -> R, b: () -> R): R = if (flag) a() else b()

inline fun <R> retrying(block: () -> R, onFailure: () -> R): R = try { block() } catch (e: Exception) { onFailure() }

// Like `buildList`: the lambda's result isn't the call's.
inline fun <E> buildThing(builder: MutableList<E>.() -> Unit): List<E> = mutableListOf<E>().apply(builder)

inline fun <R> overloaded(block: () -> R): R = block()
inline fun <R> overloaded(flag: Boolean, other: () -> R): R = other()

inline fun <R> defaulted(flag: Boolean = true, block: () -> R): R = block()

class Runner {
    inline fun <R> attempt(block: () -> R): R = block()
}

class Box<T>(val content: T) {
    inline fun <R> fold(transform: (T) -> R): R = transform(content)
}

fun value(): Int = 1

// MODULE: main(lib)
// SHUSH_RETURNS_RESULT_OF: lib.resourceScope lib.scope lib.choose#a#b lib.retrying#block lib.buildThing
// SHUSH_RETURNS_RESULT_OF: lib.overloaded#block lib.Box.fold lib.Runner.attempt lib.defaulted
// FILE: main.kt
import lib.*

// Not must-use: the checker is in `check` mode and this file isn't annotated.
fun plain(): Int = 2

suspend fun suspending() {
    resourceScope { plain() }
    <!RETURN_VALUE_NOT_USED!>resourceScope<!> { value() }
}

fun box(): String {
    scope { plain() }
    <!RETURN_VALUE_NOT_USED!>scope<!> { value() }
    // a statement before the tail is still checked
    scope {
        <!RETURN_VALUE_NOT_USED!>value<!>()
        plain()
    }
    val used = scope { value() }

    choose(true, { plain() }, { plain() })
    <!RETURN_VALUE_NOT_USED!>choose<!>(true, { value() }, { plain() })

    // only `block` was named, so `onFailure`'s result doesn't count
    retrying({ plain() }, { plain() })
    retrying({ plain() }, { value() })
    <!RETURN_VALUE_NOT_USED!>retrying<!>({ value() }, { plain() })

    <!RETURN_VALUE_NOT_USED!>buildThing<!><Int> { add(plain()) }

    overloaded { plain() }
    <!RETURN_VALUE_NOT_USED!>overloaded<!>(true) { plain() }

    // `defaulted { ... }` isn't covered: Kotlin 2.4's checker misses the lambda when an earlier defaulted argument is left out
    defaulted(false) { plain() }

    Runner().attempt { plain() }
    Runner().<!RETURN_VALUE_NOT_USED!>attempt<!> { value() }

    // `Box(1).fold { plain() }` isn't covered: on Kotlin 2.5.0-Beta2 a generic class's member is called through a
    // call-site substitution override that has no contract, so it's reported there
    Box("a").<!RETURN_VALUE_NOT_USED!>fold<!> { value() }

    return if (used == 1) "OK" else "Fail"
}
