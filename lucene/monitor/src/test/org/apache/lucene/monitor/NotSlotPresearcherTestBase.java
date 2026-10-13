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

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;

/** Presearcher tests with NotSlots enabled, in addition to all of {@link PresearcherTestBase} */
public abstract class NotSlotPresearcherTestBase extends PresearcherTestBase {

  protected abstract Presearcher createPresearcher(
      int maxNotSlots, List<CustomQueryHandler> handlers);

  @Override
  protected Presearcher createPresearcher() {
    return createPresearcher(1, List.of());
  }

  static Document doc(String... fieldValues) {
    Document doc = new Document();
    for (int i = 0; i < fieldValues.length; i += 2) {
      doc.add(new TextField(fieldValues[i], fieldValues[i + 1], Field.Store.NO));
    }
    return doc;
  }

  /** Registers {@code query} and returns {@code {queriesRun, matchCount}} for {@code doc} */
  int[] run(Presearcher presearcher, Query query, Document doc) throws IOException {
    try (Monitor monitor = new Monitor(WHITESPACE, presearcher)) {
      monitor.register(new MonitorQuery("q", query));
      MatchingQueries<QueryMatch> matches = monitor.match(doc, QueryMatch.SIMPLE_MATCHER);
      return new int[] {matches.getQueriesRun(), matches.getMatchCount()};
    }
  }

  int[] run(Presearcher presearcher, String query, Document doc) throws IOException {
    return run(presearcher, parse(query), doc);
  }

  int[] run(String query, Document doc) throws IOException {
    return run(createPresearcher(), query, doc);
  }

  static void assertRunAndMatched(int queriesRun, int matches, int[] actual) {
    assertEquals("queries run", queriesRun, actual[0]);
    assertEquals("matches", matches, actual[1]);
  }

  public void testMatchAllMinusTermExcludesDocumentsWithTheTerm() throws IOException {
    assertRunAndMatched(0, 0, run("*:* -f:t", doc("f", "t")));
    assertRunAndMatched(1, 1, run("*:* -f:t", doc("f", "x")));
    assertRunAndMatched(1, 1, run("*:* -f:t", doc("g", "y")));
  }

  /** Wraps {@code query} so that the Monitor's QueryDecomposer leaves it whole */
  static Query undecomposed(String query) {
    return new ConstantScoreQuery(parse(query));
  }

  public void testPureNegativeQuery() throws IOException {
    // The QueryDecomposer drops top-level pure negative queries, as Lucene matches nothing for them
    assertRunAndMatched(0, 0, run("-f:t", doc("f", "x")));
    // nested, it is a NotSlot
    assertRunAndMatched(0, 0, run(createPresearcher(), undecomposed("-f:t"), doc("f", "t")));
    assertRunAndMatched(1, 0, run(createPresearcher(), undecomposed("-f:t"), doc("f", "x")));
  }

  public void testNegatedDisjunction() throws IOException {
    assertRunAndMatched(0, 0, run("*:* -(y:b z:c)", doc("y", "b")));
    assertRunAndMatched(0, 0, run("*:* -(y:b z:c)", doc("z", "c")));
    assertRunAndMatched(1, 1, run("*:* -(y:b z:c)", doc("y", "q")));
  }

  public void testNegatedConjunctionNeedsTwoNotSlots() throws IOException {
    String query = "*:* -(+y:b +z:c)";
    Document both = doc("y", "b", "z", "c");
    // one NotSlot: the pass overflows and accepts every document
    assertRunAndMatched(1, 0, run(createPresearcher(1, List.of()), query, both));
    assertRunAndMatched(0, 0, run(createPresearcher(2, List.of()), query, both));
    assertRunAndMatched(1, 1, run(createPresearcher(2, List.of()), query, doc("y", "b")));
  }

  public void testDisjunctionOfNegations() throws IOException {
    // decomposed, each disjunct is its own query with one NotSlot
    assertRunAndMatched(0, 0, run("(*:* -a:x) (*:* -b:y)", doc("a", "x", "b", "y")));

    Query query = undecomposed("(*:* -a:x) (*:* -b:y)");
    Document both = doc("a", "x", "b", "y");
    assertRunAndMatched(1, 0, run(createPresearcher(1, List.of()), query, both));
    assertRunAndMatched(0, 0, run(createPresearcher(2, List.of()), query, both));
    assertRunAndMatched(1, 1, run(createPresearcher(2, List.of()), query, doc("a", "x")));
  }

  public void testInexactNegationsNeverExclude() throws IOException {
    assertRunAndMatched(1, 1, run("*:* -f:\"w1 w2\"", doc("f", "w2 w1")));
    assertRunAndMatched(1, 0, run("*:* -f:\"w1 w2\"", doc("f", "w1 w2")));
    assertRunAndMatched(1, 0, run("*:* -f:b*", doc("f", "bb")));
    assertRunAndMatched(1, 0, run("*:* -f:[a TO c]", doc("f", "b")));
  }

  public void testBatchesOnlyExcludeOnTermsEveryDocumentContains() throws IOException {
    try (Monitor monitor = newMonitor()) {
      monitor.register(new MonitorQuery("q", parse("*:* -f:t")));

      MultiMatchingQueries<QueryMatch> matches =
          monitor.match(new Document[] {doc("f", "t"), doc("f", "x")}, QueryMatch.SIMPLE_MATCHER);
      assertEquals(1, matches.getQueriesRun());
      assertEquals(0, matches.getMatchCount(0));
      assertEquals(1, matches.getMatchCount(1));

      matches =
          monitor.match(
              new Document[] {doc("f", "t", "x", "1"), doc("f", "t")}, QueryMatch.SIMPLE_MATCHER);
      assertEquals(0, matches.getQueriesRun());
    }
  }

  public void testCustomQueryHandlerNegation() throws IOException {
    Query query =
        new BooleanQuery.Builder()
            .add(MatchAllDocsQuery.INSTANCE, BooleanClause.Occur.SHOULD)
            .add(
                new OpaqueTermsQuery(Set.of(new Term("f", "x"), new Term("f", "y"))),
                BooleanClause.Occur.MUST_NOT)
            .build();
    Presearcher negatable = createPresearcher(1, List.of(new OpaqueTermsQuery.Handler(true)));
    assertRunAndMatched(0, 0, run(negatable, query, doc("f", "y")));
    assertRunAndMatched(1, 1, run(negatable, query, doc("f", "z")));

    Presearcher notNegatable = createPresearcher(1, List.of(new OpaqueTermsQuery.Handler(false)));
    assertRunAndMatched(1, 0, run(notNegatable, query, doc("f", "y")));
  }
}
