/*
 * Copyright 2013-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.features.handler;

import brave.handler.MutableSpan;
import brave.handler.SpanListener;
import brave.internal.weaklockfree.WeakConcurrentMap;
import brave.propagation.TraceContext;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import static java.util.Collections.emptyIterator;

public abstract class FinishedChildrenListener extends SpanListener {

  protected abstract void onFinish(MutableSpan parent, Iterator<MutableSpan> children);

  /** This holds the children of the current parent until the former is finished or abandoned. */
  final WeakConcurrentMap<TraceContext, TraceContext> childToParent = new WeakConcurrentMap<>();
  final ParentToChildren parentToChildren = new ParentToChildren();

  @Override public void onCreate(TraceContext parent, TraceContext context, MutableSpan span) {
    if (!context.isLocalRoot()) { // a child
      childToParent.putIfProbablyAbsent(context, parent);
      parentToChildren.add(parent, span);
    }
  }

  @Override public void onAbandon(TraceContext context, MutableSpan span) {
    if (!context.isLocalRoot()) { // a child
      TraceContext parent = childToParent.remove(context);
      parentToChildren.remove(parent, span);
    }
  }

  @Override public void onFlush(TraceContext context, MutableSpan span) {
    onAbandon(context, span);
  }

  @Override public void onOrphan(TraceContext context, MutableSpan span) {
    onAbandon(context, span);
  }

  @Override public void onFinish(TraceContext context, MutableSpan span) {
    // There could be a lot of children. Instead of copying the list result, expose the iterator.
    // The main goal is to not add too much overhead as this is invoked on the same thread as
    // application code which implicitly call Span.finish() through instrumentation.
    childToParent.remove(context);
    Set<MutableSpan> children = parentToChildren.remove(context);
    Iterator<MutableSpan> child = children != null ? children.iterator() : emptyIterator();
    FinishedChildrenListener.this.onFinish(span, child);
  }

  static final class ParentToChildren {
    final WeakConcurrentMap<TraceContext, Set<MutableSpan>> delegate = new WeakConcurrentMap<>();

    void add(TraceContext parent, MutableSpan child) {
      Set<MutableSpan> children = delegate.getIfPresent(parent);
      if (children == null) {
        children = new LinkedHashSet<>();
        Set<MutableSpan> old = delegate.putIfProbablyAbsent(parent, children);
        if (old != null) children = old;
      }
      children.add(child);
    }

    void remove(TraceContext parent, MutableSpan child) {
      Set<MutableSpan> children = delegate.getIfPresent(parent);
      if (children != null) children.remove(child);
    }

    Set<MutableSpan> remove(TraceContext parent) {
      return delegate.remove(parent);
    }
  }
}
