// WITH_EXTRA_CHECKERS
// SHUSH_RULE: generated=REDUNDANT_VISIBILITY_MODIFIER
// SHUSH_FILES: generated=**/generated/**
// SHUSH_RULE: api=REDUNDANT_VISIBILITY_MODIFIER
// SHUSH_ANNOTATED_DECLARATIONS: api=main.ExplicitApi
// FILE: generated/Gen.kt
package gen

public class Generated {
    public fun f() {}
}

// FILE: main.kt
package main

annotation class ExplicitApi

<!REDUNDANT_VISIBILITY_MODIFIER!>public<!> class NotGenerated {
    <!REDUNDANT_VISIBILITY_MODIFIER!>public<!> fun f() {}
}

@ExplicitApi
public class Api {
    public fun f() {}
}
