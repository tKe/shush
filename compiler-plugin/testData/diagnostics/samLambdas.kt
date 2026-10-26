// RETURN_VALUE_CHECKER_MODE: full
// SHUSH_RULE: batch=RETURN_VALUE_NOT_USED batch=RETURN_VALUE_NOT_USED_COERCION
// SHUSH_CALL_LAMBDAS: batch=io.temporal.client.BatchRequest.add
// Lambdas SAM-converted to Java functional interfaces, as with Temporal's `BatchRequest.add(Functions.Proc)`.
// FILE: io/temporal/workflow/Functions.java
package io.temporal.workflow;

public final class Functions {
    @FunctionalInterface
    public interface Proc { void apply(); }

    @FunctionalInterface
    public interface Proc1<A1> { void apply(A1 a1); }

    @FunctionalInterface
    public interface Func<R> { R apply(); }

    @FunctionalInterface
    public interface Func1<A1, R> { R apply(A1 a1); }
}

// FILE: io/temporal/client/BatchRequest.java
package io.temporal.client;

import io.temporal.workflow.Functions;

public interface BatchRequest {
    void add(Functions.Proc request);
    <A1> void add(Functions.Proc1<A1> request, A1 arg1);
    void add(Functions.Func<?> request);
    <A1> void add(Functions.Func1<A1, ?> request, A1 arg1);
}

// FILE: test.kt
package test

import io.temporal.client.BatchRequest

fun signalWithStart(signals: BatchRequest.() -> Unit) {}

interface MyWorkflow {
    fun start(): String
    fun startWith(arg: Int): String
    fun signal()
}

fun batch(request: BatchRequest, workflow: MyWorkflow) {
    request.add { workflow.start() }
    request.add({ workflow.startWith(it) }, 1)
    request.add(workflow::start)
    request.add { workflow.signal() }
}

fun nested(request: BatchRequest, workflow: MyWorkflow, items: List<Int>) {
    items.forEach { request.add { workflow.startWith(it) } }
    signalWithStart {
        add { workflow.start() }
        add { workflow.startWith(1) }
    }
}

fun notTargeted(workflow: MyWorkflow) {
    Runnable { workflow.<!RETURN_VALUE_NOT_USED_COERCION!>start<!>() }.run()
}
