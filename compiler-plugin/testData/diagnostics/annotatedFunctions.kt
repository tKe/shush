// RETURN_VALUE_CHECKER_MODE: full
// SHUSH_RULE: templates=RETURN_VALUE_NOT_USED templates=RETURN_VALUE_NOT_USED_COERCION
// SHUSH_ANNOTATED_FUNCTIONS: templates=test.Template templates=test/Outer.NestedTemplate
// FILE: test.kt
package test

annotation class Template

class Outer {
    annotation class NestedTemplate
}

annotation class NotConfigured

class A {
    fun f(): Int = 1
}

@Template
fun template(block: () -> Unit) {}

@Template
fun <T> templateWithReceiver(block: A.() -> T): T = TODO()

@Outer.NestedTemplate
fun nestedTemplate(vararg blocks: () -> Unit) {}

@NotConfigured
fun notConfigured(block: () -> Unit) {}

fun plain(block: () -> Unit) {}

fun annotated(a: A) {
    template { a.f() }
    template {
        a.f()
        a.f()
    }
    val r: Int = templateWithReceiver { f(); f() }
    nestedTemplate({ a.f() }, { a.f(); a.f() })
}

fun notAnnotated(a: A) {
    notConfigured { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
    plain { a.<!RETURN_VALUE_NOT_USED_COERCION!>f<!>() }
}
