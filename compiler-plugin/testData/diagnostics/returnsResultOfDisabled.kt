// RETURN_VALUE_CHECKER_MODE: check
// FILE: test.kt
package test

// Without returnsResultOf, stdlib's `fold` and `getOrElse` are must-use whatever their lambdas return.
fun plain(): Int = 1

fun calls(result: Result<Int>, list: List<Int>) {
    result.<!RETURN_VALUE_NOT_USED!>fold<!>({ plain() }, { plain() })
    list.<!RETURN_VALUE_NOT_USED!>getOrElse<!>(5) { plain() }
}
