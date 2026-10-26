// RETURN_VALUE_CHECKER_MODE: full
// SHUSH_IGNORABLE_RETURN_TYPES: test.Empty test/Responses.Done
// FILE: test.kt
package test

// Like `com.google.protobuf.Empty`: a generated message with no fields.
class Empty

class Responses {
    class Done
}

typealias Nothing2 = Empty

interface Service {
    suspend fun ping(): Empty
}

class Stub : Service {
    override suspend fun ping(): Empty = Empty()
    fun done(): Responses.Done = Responses.Done()
    fun aliased(): Nothing2 = Empty()
    fun maybe(): Empty? = null
    fun inferred() = Empty()
    fun value(): Int = 1
    @IgnorableReturnValue fun annotated(): Empty = Empty()
}

fun empty(): Empty = Empty()

suspend fun calls(stub: Stub, service: Service) {
    stub.ping()
    service.ping()
    stub.done()
    stub.aliased()
    stub.annotated()
    empty()
    stub.<!RETURN_VALUE_NOT_USED!>maybe<!>()
    stub.<!RETURN_VALUE_NOT_USED!>inferred<!>()
    stub.<!RETURN_VALUE_NOT_USED!>value<!>()
    <!RETURN_VALUE_NOT_USED!>Empty<!>()
}
