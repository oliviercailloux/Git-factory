package io.github.oliviercailloux.git.factory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Graph;
import com.google.common.graph.Traverser;
import io.github.oliviercailloux.jaris.throwing.TFunction;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.function.Function;

/**
 * Provides, when given a graph of paths, a {@link TFunction} from {@link Path} to {@code T}, either
 * directly (via {@link #function}) or by assigning values from an {@link Iterable} to nodes visited
 * in breadth-first order over a given graph (via {@link #onBreadthFirst}).
 *
 * <p>
 * Immutable.
 */
class OnDemandFunctionOfPath<T> {

  /**
   * Returns an instance that, on {@link #summon}, returns {@code f} directly.
   *
   * <p>
   * The returned value does not depend on the graph's traversal order; the function is applied per
   * path.
   */
  public static <T> OnDemandFunctionOfPath<T> function(TFunction<Path, T, IOException> f) {
    return new OnDemandFunctionOfPath<>(checkNotNull(f), null);
  }

  /**
   * Returns an instance that, on {@link #summon}, traverses the graph breadth-first starting from
   * all roots (nodes with in-degree 0) simultaneously, and assigns one element of {@code values} to
   * each node in that order.
   *
   * <p>
   * Each call to {@link #summon} calls {@link Iterable#iterator()} afresh, so any collection-backed
   * iterable (e.g. a {@link java.util.List}) can be reused across multiple calls. An infinite
   * iterable (where {@code iterator()} creates a fresh counter each time) is also safe.
   *
   * <p>
   * Among multiple roots, their relative order in the traversal follows the graph's node iteration
   * order (insertion order for {@link com.google.common.graph.ImmutableGraph}).
   */
  public static <T> OnDemandFunctionOfPath<T> onBreadthFirst(Iterable<T> values) {
    return new OnDemandFunctionOfPath<>(null, checkNotNull(values));
  }

  /**
   * Non-null iff {@code values} is null.
   */
  private final TFunction<Path, T, IOException> f;
  private final Iterable<T> values;

  private OnDemandFunctionOfPath(TFunction<Path, T, IOException> f, Iterable<T> values) {
    this.f = f;
    this.values = values;
    checkArgument((f == null) != (values == null));
  }

  /**
   * Returns a function assigning a {@code T} to each node of {@code graph}.
   *
   * <p>
   * If created via {@link #function}, returns the wrapped function. If created via
   * {@link #onBreadthFirst}, traverses {@code graph} breadth-first starting from all roots
   * simultaneously, consuming one element of the iterable per visited node, and returns the result
   * as a function (which throws {@link IllegalArgumentException} for unknown paths).
   */
  public TFunction<Path, T, IOException> summon(Graph<Path> graph) {
    if (f != null) {
      return f;
    }
    ImmutableSet<Path> roots = graph.nodes().stream().filter(n -> graph.inDegree(n) == 0)
        .collect(ImmutableSet.toImmutableSet());
    Iterator<T> it = values.iterator();
    ImmutableMap.Builder<Path, T> builder = ImmutableMap.builder();
    for (Path node : Traverser.<Path>forGraph(graph::successors).breadthFirst(roots)) {
      builder.put(node, it.next());
    }
    Function<Path, T> mapped = Functions.forMap(builder.build());
    return mapped::apply;
  }
}
