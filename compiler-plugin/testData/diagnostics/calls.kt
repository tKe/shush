// RETURN_VALUE_CHECKER_MODE: full
// SHUSH_RULE: empty=RETURN_VALUE_NOT_USED empty=RETURN_VALUE_NOT_USED_COERCION
// SHUSH_CALLS: empty=test.Stub.empty empty=test.emptyOf
// FILE: test.kt
package test

// Like `com.google.protobuf.Empty`: a generated message with no fields.
class Empty

class Stub {
    fun empty(): Empty = Empty()
    fun empty(request: Int): Empty = Empty()
    fun value(): Int = 1
}

fun emptyOf(value: Int): Empty = Empty()

fun calls(stub: Stub) {
    stub.empty()
    stub.empty(1)
    emptyOf(1)
    stub.<!RETURN_VALUE_NOT_USED!>value<!>()
}

// A call whose completion is postponed, inside a lambda passed to another call.
fun postponed(stub: Stub, items: List<Int>) {
    items.forEach { stub.empty(it) }
    items.forEach { stub.<!RETURN_VALUE_NOT_USED_COERCION!>value<!>() }
}

fun safeCalls(stub: Stub?) {
    stub?.empty()
    stub?.<!RETURN_VALUE_NOT_USED!>value<!>()
}

// A user-written @Suppress on the call is merged into, not repeated (no REPEATED_ANNOTATION).
fun userSuppress(stub: Stub) {
    @Suppress("DEPRECATION") stub.empty()
}
