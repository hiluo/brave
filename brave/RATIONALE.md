# brave rationale

## SpanListener
There are a few reasons why `SpanListener` exists.

For example, we need to manipulate Baggage without overriding `PropagationFactory`. For example,
some baggage are derived (parsed from other headers). Given a create hook, one can post process
baggage before the span is visible to the user.

Another example is parent-child relationships. With a create hook someone can do things like count
children (needed by [stackdriver model](https://cloud.google.com/trace/docs/reference/v2/rpc/google.devtools.cloudtrace.v2#google.devtools.cloudtrace.v2.Span)).

Finally, some services require the entire "local root" to be reported at the same time. The only
known example of this is [DataDog](https://github.com/DataDog/dd-trace-java/blob/406b324a82b482d7d8ad3faa5f9ccdd307c72308/dd-trace-ot/src/main/java/datadog/trace/common/writer/Writer.java#L20).
A create hook can be used track a local root, allowing buffering of all descendants.

### What's with all the termination hooks?
Most users will be unaware that there's an end state besides `Span.finish()`, but here are all
possibilities:

 * `Span.abandon()` - if a speculative context
 * `Span.flush()` - if intentionally reported incomplete
 * `Span.finish()` - normal case
 * "orphan" - a possibly empty span reported incomplete due to garbage collection

The above hooks ensure every created trace context has an end state. This is particularly different
than `FinishedSpanHandler`, which doesn't see some nuance such as allocated, but unused contexts,
which would end up orphaned. The cause of these is usually a leak.

## CorrelationScopeDecorator

### Why hold the initial values even when they match?

The value read at the beginning of a scope is currently held when there's a
chance the field can be updated later:

* `CorrelationField.dirty()`
  * Always revert because someone else could have changed it (ex via `MDC`)
* `CorrelationField.flushOnUpdate()`
  * Revert if only when we changed it (ex via `BaggageField.updateValue()`)

This is because users generally expect data to be "cleaned up" when a scope
completes, even if it was written mid-scope.

https://github.com/spring-cloud/spring-cloud-sleuth/issues/1416

If we delayed reading the value, until update, it could be different, due to
nesting of scopes or out-of-band updates to the correlation context. Hence, we
only have one opportunity to read the value: at scope creation time.

### CorrelationContext

The `CorrelationContext` design is based on pragmatism due to what's available
in underlying log contexts. It mainly avoids operations that accept Map as this
implies overhead to construct and iterate over.

Here is an example source from Log4J 2:
```java
public interface ThreadContextMap {
 void clear();

 boolean containsKey(String var1);

 String get(String var1);

 Map<String, String> getCopy();

 Map<String, String> getImmutableMapOrNull();

 boolean isEmpty();

 void put(String var1, String var2);

 void remove(String var1);
}
```

#### Why not guard on previous value when doing an update?

While the current design is optimized for log contexts (or those similar such
as JFR), you can reasonably think of this like generic contexts such as gRPC
and Armeria:
https://github.com/line/armeria/blob/master/core/src/main/java/com/linecorp/armeria/common/RequestContextStorage.java#L88
https://github.com/grpc/grpc-java/blob/master/context/src/main/java/io/grpc/ThreadLocalContextStorage.java

The above include design facets to help with overlapping scopes, notably
comparing the current value vs the one written prior to reverting a value.

There are two main reasons we aren't doing this is in the current
`CorrelationContext` api. First, this feature was designed for contexts which
don't have these operators. To get a compare-and-set outcome would require
reading back the logging context manually. This has two problems, one is
performance impact and the other is that the value can always be updated
out-of-band. Unlike gRPC context, logging contexts are plain string keys, and
easy to clobber by users or other code. It is therefore hard to tell if
inconsistency is due to things under your control or not (ex bad
instrumentation vs 3rd party code).

The other reason is that this is only used internally where we control the
call sites. These call sites are already try/finally in nature, which addresses
the concern we can control.
