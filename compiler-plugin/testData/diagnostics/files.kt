// WITH_EXTRA_CHECKERS
// DIAGNOSTICS: +UNUSED_VARIABLE
// SHUSH_RULE: generated=UNUSED_VARIABLE
// SHUSH_FILES: generated=**/generated/**
// SHUSH_RULE: deprecated=DEPRECATION
// SHUSH_FILES: deprecated=**/Both.kt
// SHUSH_RULE: declarations=DEPRECATION
// SHUSH_ANNOTATED_DECLARATIONS: declarations=lib.Legacy
// FILE: lib.kt
package lib

annotation class Legacy

@Deprecated("old")
fun old(): Int = 1

// FILE: generated/Gen.kt
package gen

import lib.*

fun generated() {
    val x = <!DEPRECATION!>old<!>()
}

class Generated {
    fun member() {
        val x = <!DEPRECATION!>old<!>()
    }
}

// files + annotatedDeclarations on the same file.
@Legacy
fun legacy() {
    val x = old()
}

// FILE: generated/Both.kt
package gen

import lib.*

// Two file rules on one file merge their names.
fun both() {
    val x = old()
}

// FILE: generated/UserSuppressPositional.kt
@file:Suppress("DEPRECATION")
package gen

import lib.*

fun userSuppressPositional() {
    val x = old()
}

// FILE: generated/UserSuppressNamed.kt
@file:Suppress(names = ["DEPRECATION"])
package gen

import lib.*

fun userSuppressNamed() {
    val x = old()
}

// FILE: main.kt
package main

import lib.*

fun notGenerated() {
    val <!UNUSED_VARIABLE!>x<!> = <!DEPRECATION!>old<!>()
}

@Legacy
fun legacy() {
    val <!UNUSED_VARIABLE!>x<!> = old()
}

// FILE: UserSuppressNotGenerated.kt
@file:Suppress("DEPRECATION")
package main

import lib.*

fun userSuppressNotGenerated() {
    val <!UNUSED_VARIABLE!>x<!> = old()
}
