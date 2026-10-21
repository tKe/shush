// SHUSH_RULE: generated=UNUSED_VARIABLE generated=DEPRECATION
// SHUSH_FILES: generated=**/generated/**
// SHUSH_ANNOTATED_DECLARATIONS: generated=test.Generated
// SHUSH_RULE: unused=UNUSED_VARIABLE
// SHUSH_ANNOTATED_DECLARATIONS: unused=test.Unused
// FILE: lib.kt
package test

annotation class Generated

@Retention(AnnotationRetention.RUNTIME)
annotation class Unused

@Deprecated("old")
fun old(): Int = 1

// FILE: generated/Gen.kt
@file:Suppress("REDUNDANT_VISIBILITY_MODIFIER")
@file:JvmName("GeneratedKt")
package test

public fun generated(): String {
    val unused = old()
    return "O"
}

// FILE: box.kt
package test

@Generated
class GeneratedClass {
    val value: Int = old()

    fun k(): String {
        val unused = old()
        return "K"
    }
}

@Unused
@Suppress("DEPRECATION")
fun userSuppressed(): Int {
    val unused = 2
    return old()
}

@Unused
@Suppress(names = ["DEPRECATION"])
fun userSuppressedNamed(): Int = old()

fun box(): String {
    @Generated
    fun local(): Int {
        val unused = 0
        return old()
    }

    if (GeneratedClass().value + local() + userSuppressed() + userSuppressedNamed() != 4) return "Fail: values"
    // Our synthetic @Suppress must not replace the user's other annotations in the output.
    val annotations = Class.forName("test.BoxKt").getMethod("userSuppressed").annotations.toList()
    if (annotations.singleOrNull() !is Unused) return "Fail: annotations $annotations"
    return generated() + GeneratedClass().k()
}
