// WITH_EXTRA_CHECKERS
// DIAGNOSTICS: +UNUSED_VARIABLE
// SHUSH_RULE: generated=UNUSED_VARIABLE generated=DEPRECATION
// SHUSH_ANNOTATED_DECLARATIONS: generated=test.Generated
// SHUSH_RULE: unused=UNUSED_VARIABLE
// SHUSH_ANNOTATED_DECLARATIONS: unused=test/Annotations.Unused
// FILE: test.kt
package test

annotation class Generated

object Annotations {
    annotation class Unused
}

@Deprecated("old")
fun old(): Int = 1

@Generated
fun topLevel() {
    val x = old()
}

fun notAnnotated() {
    val <!UNUSED_VARIABLE!>x<!> = <!DEPRECATION!>old<!>()
}

// Members (and their bodies) of an annotated class are covered too.
@Generated
class GeneratedClass {
    fun member() {
        val x = old()
    }

    val getter: Int
        get() {
            val x = 1
            return old()
        }

    val initialized: Int = old()

    class Nested {
        fun f() {
            val x = old()
        }
    }
}

class NotAnnotatedClass {
    fun member() {
        val <!UNUSED_VARIABLE!>x<!> = <!DEPRECATION!>old<!>()
    }
}

@Generated
val property: Int
    get() {
        val x = 1
        return old()
    }

@Generated
val initializedProperty: Int = old()

val notAnnotatedProperty: Int = <!DEPRECATION!>old<!>()

fun localDeclarations() {
    @Generated
    fun local() {
        val x = old()
    }
    local()

    @Generated
    class LocalClass {
        fun f() {
            val x = old()
        }
    }
    LocalClass().f()

    fun notAnnotatedLocal() {
        val <!UNUSED_VARIABLE!>x<!> = <!DEPRECATION!>old<!>()
    }
    notAnnotatedLocal()
}

// Suppressions merge into an existing @Suppress (`@Suppress` isn't repeatable), and the user's own still apply.
@Annotations.Unused
@Suppress("DEPRECATION")
fun userSuppressPositional() {
    val x = old()
}

@Annotations.Unused
@Suppress(names = ["DEPRECATION"])
fun userSuppressNamed() {
    val x = old()
}

@Annotations.Unused
@Suppress("UNUSED_VARIABLE", "DEPRECATION")
fun userSuppressAlreadyIncluded() {
    val x = old()
}

@Annotations.Unused
fun onlyRuleDiagnostics() {
    val x = <!DEPRECATION!>old<!>()
}

@Suppress("DEPRECATION")
fun userSuppressNotAnnotated() {
    val <!UNUSED_VARIABLE!>x<!> = old()
}

// Several rules on one declaration merge their names.
@Generated
@Annotations.Unused
fun bothRules() {
    val x = old()
}
