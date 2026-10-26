// DIAGNOSTICS: +RETURN_VALUE_NOT_USED
// MODULE: lib
// SHUSH_IGNORABLE_RETURN_TYPES: lib.Empty
// FILE: lib.kt
@file:MustUseReturnValues
package lib

class Empty

fun ping(): Empty = Empty()

fun count(): Int = 1

// MODULE: main(lib)
// FILE: main.kt
import lib.*

fun box(): String {
    ping()
    <!RETURN_VALUE_NOT_USED!>count<!>()
    return "OK"
}
