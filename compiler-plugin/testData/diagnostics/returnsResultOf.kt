// RETURN_VALUE_CHECKER_MODE: check
// SHUSH_RETURNS_RESULT_OF: kotlin.fold kotlin.collections.getOrElse kotlin.collections.buildList
// FILE: test.kt
package test

// Not must-use in `check` mode; stdlib functions are.
fun plain(): Int = 1

fun folds(result: Result<Int>) {
    result.fold({ plain() }, { plain() })
    result.<!RETURN_VALUE_NOT_USED!>fold<!>({ listOf(it) }, { listOf(0) })
    result.<!RETURN_VALUE_NOT_USED!>fold<!>({ plain() }, { listOf(0) })
    val used = result.fold({ listOf(it) }, { listOf(0) })
}

fun others(list: List<Int>) {
    list.getOrElse(5) { plain() }
    // `buildList`'s lambda returns Unit, not the list, so it gets no contract
    <!RETURN_VALUE_NOT_USED!>buildList<!> { add(plain()) }
}
