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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.TermQuery;

/**
 * Matches documents containing any of a set of terms, but is a leaf to the {@link QueryVisitor}
 * API, so that the presearcher relies on a {@link CustomQueryHandler} for it
 */
final class OpaqueTermsQuery extends Query {

  final Set<Term> terms;

  OpaqueTermsQuery(Set<Term> terms) {
    this.terms = new TreeSet<>(terms);
  }

  @Override
  public Query rewrite(IndexSearcher indexSearcher) {
    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    for (Term term : terms) {
      builder.add(new TermQuery(term), BooleanClause.Occur.SHOULD);
    }
    return builder.build();
  }

  @Override
  public void visit(QueryVisitor visitor) {
    visitor.visitLeaf(this);
  }

  @Override
  public String toString(String field) {
    return "opaque" + terms;
  }

  @Override
  public boolean equals(Object other) {
    return sameClassAs(other) && terms.equals(((OpaqueTermsQuery) other).terms);
  }

  @Override
  public int hashCode() {
    return 31 * classHash() + terms.hashCode();
  }

  /** Handles {@link OpaqueTermsQuery}, optionally under negation too */
  static final class Handler implements CustomQueryHandler {

    private final boolean negatable;

    Handler(boolean negatable) {
      this.negatable = negatable;
    }

    @Override
    public QueryTree handleQuery(Query query, TermWeightor termWeightor) {
      if (query instanceof OpaqueTermsQuery opaque) {
        List<Function<TermWeightor, QueryTree>> children = new ArrayList<>();
        for (Term term : opaque.terms) {
          children.add(w -> QueryTree.term(term, w));
        }
        return QueryTree.disjunction(children, termWeightor);
      }
      return null;
    }

    @Override
    public QueryTree handleNegatedQuery(Query query, TermWeightor termWeightor) {
      if (negatable && query instanceof OpaqueTermsQuery opaque) {
        return QueryTree.notSlot(opaque.terms, termWeightor);
      }
      return null;
    }
  }
}
