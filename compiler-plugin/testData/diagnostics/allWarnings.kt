// WITH_EXTRA_CHECKERS
// DIAGNOSTICS: +UNUSED_VARIABLE
// SHUSH_RULE: generated=warnings
// SHUSH_FILES: generated=**/generated/**
// SHUSH_RULE: legacy=warnings
// SHUSH_ANNOTATED_DECLARATIONS: legacy=lib.Legacy
// SHUSH_RULE: block=warnings
// SHUSH_CALLS: block=lib.quietly
// FILE: lib.kt
package lib

annotation class Legacy

@Deprecated("old")
fun old(): Int = 1

fun quietly(block: () -> Unit) = block()

// FILE: generated/Gen.kt
package gen

import lib.*

// The compiler's own `warnings` name suppresses every warning, whatever its name.
public fun generated() {
    val x = old()
}

// FILE: generated/UserSuppress.kt
@file:Suppress("DEPRECATION")
package gen

import lib.*

public fun userSuppress() {
    val x = old()
}

// FILE: main.kt
package main

import lib.*

<!REDUNDANT_VISIBILITY_MODIFIER!>public<!> fun notGenerated() {
    val <!UNUSED_VARIABLE!>x<!> = <!DEPRECATION!>old<!>()
}

@Legacy
public fun legacy() {
    val x = old()
}

fun block() {
    quietly {
        val x = old()
    }
}
