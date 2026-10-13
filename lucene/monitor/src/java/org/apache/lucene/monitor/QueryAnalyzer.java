/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.monitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.SynonymQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;

/**
 * Class to analyze and extract terms from a lucene query, to be used by a {@link Presearcher} in
 * indexing.
 */
class QueryAnalyzer {

  private final BiFunction<Query, TermWeightor, QueryTree> unknownQueryMapper;
  private final BiFunction<Query, TermWeightor, QueryTree> negatedQueryMapper;
  private final boolean isNegationOptimized;

  QueryAnalyzer(List<CustomQueryHandler> queryTreeBuilders) {
    this(queryTreeBuilders, false);
  }

  /**
   * @param isNegationOptimized whether negated clauses become NotSlots, rather than being ignored
   */
  QueryAnalyzer(List<CustomQueryHandler> queryTreeBuilders, boolean isNegationOptimized) {
    this.unknownQueryMapper = buildMapper(queryTreeBuilders, h -> h::handleQuery);
    this.negatedQueryMapper = buildMapper(queryTreeBuilders, h -> h::handleNegatedQuery);
    this.isNegationOptimized = isNegationOptimized;
  }

  QueryAnalyzer() {
    this.unknownQueryMapper = (_, _) -> null;
    this.negatedQueryMapper = (_, _) -> null;
    this.isNegationOptimized = false;
  }

  private static BiFunction<Query, TermWeightor, QueryTree> buildMapper(
      List<CustomQueryHandler> mappers,
      Function<CustomQueryHandler, BiFunction<Query, TermWeightor, QueryTree>> method) {
    return (q, w) -> {
      for (CustomQueryHandler mapper : mappers) {
        QueryTree qt = method.apply(mapper).apply(q, w);
        if (qt != null) {
          return qt;
        }
      }
      return null;
    };
  }

  /**
   * Create a {@link QueryTree} from a passed in Query or Filter
   *
   * @param luceneQuery the query to analyze
   * @return a QueryTree describing the analyzed query
   */
  QueryTree buildTree(Query luceneQuery, TermWeightor weightor) {
    QueryBuilder builder = new QueryBuilder(false);
    luceneQuery.visit(builder);
    return builder.apply(weightor);
  }

  /**
   * Builds the tree for a group of clauses
   *
   * <p>With negation optimized, NOT is pushed down to the terms (De Morgan): a builder for a
   * negative clause builds the negation of its clauses, so its tree operator is flipped, and its
   * terms become NotSlots. Anything that cannot be negated exactly becomes ANY.
   */
  private class QueryBuilder extends QueryVisitor implements Function<TermWeightor, QueryTree> {

    final boolean isNegativeClause;
    final List<Function<TermWeightor, QueryTree>> children = new ArrayList<>();
    final Set<Term> negatedTerms = new LinkedHashSet<>();

    QueryBuilder(boolean isNegativeClause) {
      this.isNegativeClause = isNegativeClause;
    }

    /** Whether the clauses of this builder are combined with OR */
    boolean or() {
      return false;
    }

    @Override
    public QueryVisitor getSubVisitor(BooleanClause.Occur occur, Query parent) {
      if (isNegativeClause && !isExactUnderNegation(parent, occur)) {
        children.add(_ -> QueryTree.anyTerm("NOT " + parent));
        return QueryVisitor.EMPTY_VISITOR;
      }
      if (occur == BooleanClause.Occur.MUST || occur == BooleanClause.Occur.FILTER) {
        QueryBuilder n = new QueryBuilder(isNegativeClause);
        children.add(n);
        return n;
      }
      if (occur == BooleanClause.Occur.MUST_NOT) {
        // Check if we're in a pure negative disjunction. Lucene matches nothing for a pure
        // negative query, so under negation it can't be pushed down either.
        if (parent instanceof BooleanQuery bq
            && isPureNegative(bq)
            && (!isNegationOptimized || isNegativeClause)) {
          children.add(_ -> QueryTree.anyTerm("PURE NEGATIVE QUERY[" + parent + "]"));
          return QueryVisitor.EMPTY_VISITOR;
        }
        if (!isNegationOptimized) {
          return QueryVisitor.EMPTY_VISITOR;
        }
        // AND_i NOT q_i = NOT(OR_i q_i)
        Disjunction n = new Disjunction(!isNegativeClause);
        children.add(n);
        return n;
      }
      // It's a disjunction clause.  If the parent has MUST or FILTER clauses, we can
      // ignore it
      if (parent instanceof BooleanQuery bq && hasRequiredClauses(bq)) {
        return QueryVisitor.EMPTY_VISITOR;
      }
      Disjunction n = new Disjunction(isNegativeClause);
      children.add(n);
      return n;
    }

    @Override
    public void consumeTerms(Query query, Term... terms) {
      if (isNegativeClause) {
        if (query instanceof TermQuery
            || query instanceof SynonymQuery
            || query instanceof TermInSetQuery) {
          negatedTerms.addAll(Arrays.asList(terms));
        } else {
          children.add(_ -> QueryTree.anyTerm("NOT " + query));
        }
        return;
      }
      for (Term term : terms) {
        children.add(w -> QueryTree.term(term, w));
      }
    }

    @Override
    public void visitLeaf(Query query) {
      if (isNegativeClause) {
        children.add(
            w -> {
              QueryTree q = negatedQueryMapper.apply(query, w);
              if (q == null) {
                return QueryTree.anyTerm("NOT " + query);
              }
              return q;
            });
        return;
      }
      children.add(
          w -> {
            QueryTree q = unknownQueryMapper.apply(query, w);
            if (q == null) {
              return QueryTree.anyTerm(query.toString());
            }
            return q;
          });
    }

    @Override
    public QueryTree apply(TermWeightor termWeightor) {
      boolean isDisjunction = or() ^ isNegativeClause;
      List<Function<TermWeightor, QueryTree>> all = children;
      if (!negatedTerms.isEmpty()) {
        all = new ArrayList<>(children);
        if (isDisjunction) {
          // NOT a OR NOT b: each term is a NotSlot of its own
          for (Term term : negatedTerms) {
            all.add(w -> QueryTree.notSlot(Set.of(term), w));
          }
        } else {
          // NOT a AND NOT b: one NotSlot, a document must contain none of the terms
          all.add(w -> QueryTree.notSlot(negatedTerms, w));
        }
      }
      if (all.isEmpty() && isNegationOptimized) {
        return QueryTree.anyTerm("NO CLAUSES");
      }
      if (isDisjunction) {
        return QueryTree.disjunction(all, termWeightor);
      }
      return QueryTree.conjunction(all, termWeightor);
    }
  }

  private class Disjunction extends QueryBuilder {

    Disjunction(boolean isNegativeClause) {
      super(isNegativeClause);
    }

    @Override
    boolean or() {
      return true;
    }
  }

  /** Whether NOT {@code parent} can be expressed exactly in terms of its {@code occur} clauses */
  private static boolean isExactUnderNegation(Query parent, BooleanClause.Occur occur) {
    if (parent instanceof BooleanQuery bq) {
      return bq.getMinimumNumberShouldMatch() == 0;
    }
    if (parent instanceof BoostQuery || parent instanceof ConstantScoreQuery) {
      return true;
    }
    if (parent instanceof SynonymQuery) {
      return occur == BooleanClause.Occur.SHOULD;
    }
    return false;
  }

  private static boolean isPureNegative(BooleanQuery bq) {
    return bq.clauses().stream().allMatch(c -> c.occur() == BooleanClause.Occur.MUST_NOT);
  }

  private static boolean hasRequiredClauses(BooleanQuery bq) {
    return bq.clauses().stream()
        .anyMatch(
            c -> c.occur() == BooleanClause.Occur.MUST || c.occur() == BooleanClause.Occur.FILTER);
  }
}
