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

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.search.Query;

/**
 * Builds a {@link QueryTree} for a query that needs custom treatment
 *
 * <p>The default query analyzers will use the QueryVisitor API to extract terms from queries. If
 * different handling is needed, implement a CustomQueryHandler and pass it to the presearcher
 */
public interface CustomQueryHandler {

  /** Builds a {@link QueryTree} node from a query */
  QueryTree handleQuery(Query query, TermWeightor termWeightor);

  /**
   * Builds a {@link QueryTree} node for NOT {@code query}, used when the presearcher optimizes
   * negations
   *
   * <p>The node must select every document that does not match {@code query}; for example a {@link
   * QueryTree#notSlot(java.util.Set, TermWeightor) NotSlot} over the terms of a query that matches
   * exactly the documents containing any of them. Return null if that cannot be guaranteed; the
   * negated query is then selected for every document. The node returned by {@link
   * #handleQuery(Query, TermWeightor)} is not used under negation, as it may select more documents
   * than the query matches.
   */
  default QueryTree handleNegatedQuery(Query query, TermWeightor termWeightor) {
    return null;
  }

  /** Adds additional processing to the {@link TokenStream} over a document's terms index */
  default TokenStream wrapTermStream(String field, TokenStream in) {
    return in;
  }
}
