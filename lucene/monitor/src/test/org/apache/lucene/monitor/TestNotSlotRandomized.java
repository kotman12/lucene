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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.SynonymQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BytesRef;

/**
 * Random queries and documents: presearchers with NotSlots must never drop a query that matches,
 * compared to a Monitor that runs every query
 */
public class TestNotSlotRandomized extends LuceneTestCase {

  private static final String[] FIELDS = {"f0", "f1", "f2"};
  private static final String[] VALUES = {"a", "b", "c", "d", "e"};
  private static final Analyzer ANALYZER = new WhitespaceAnalyzer();

  private static String field(Random r) {
    return FIELDS[r.nextInt(FIELDS.length)];
  }

  private static String value(Random r) {
    return VALUES[r.nextInt(VALUES.length)];
  }

  private static Query randomLeaf(Random r) {
    String field = field(r);
    return switch (r.nextInt(12)) {
      case 0, 1, 2, 3 -> new TermQuery(new Term(field, value(r)));
      case 4 -> new PhraseQuery(field, value(r), value(r));
      case 5 -> new PrefixQuery(new Term(field, value(r)));
      case 6 -> TermRangeQuery.newStringRange(field, "b", "d", r.nextBoolean(), r.nextBoolean());
      case 7 -> MatchAllDocsQuery.INSTANCE;
      case 8 ->
          new SynonymQuery.Builder(field)
              .addTerm(new Term(field, value(r)))
              .addTerm(new Term(field, value(r)))
              .build();
      case 9 ->
          new RegexpQuery(new Term(field, r.nextBoolean() ? value(r) + ".*" : "." + value(r)));
      case 10 ->
          new TermInSetQuery(
              field,
              r.nextBoolean()
                  ? List.of(new BytesRef(value(r)))
                  : List.of(new BytesRef(value(r)), new BytesRef(value(r))));
      default -> {
        Set<Term> terms = new TreeSet<>();
        for (int i = 1 + r.nextInt(2); i > 0; i--) {
          terms.add(new Term(field, value(r)));
        }
        yield new OpaqueTermsQuery(terms);
      }
    };
  }

  private static Query randomQuery(Random r, int depth) {
    if (depth == 0 || r.nextInt(4) == 0) {
      return randomLeaf(r);
    }
    switch (r.nextInt(8)) {
      case 0:
        return new BoostQuery(randomQuery(r, depth - 1), 2);
      case 1:
        return new ConstantScoreQuery(randomQuery(r, depth - 1));
      default:
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int shoulds = 0;
        for (int i = 1 + r.nextInt(4); i > 0; i--) {
          BooleanClause.Occur occur =
              BooleanClause.Occur.values()[r.nextInt(BooleanClause.Occur.values().length)];
          if (occur == BooleanClause.Occur.SHOULD) {
            shoulds++;
          }
          builder.add(randomQuery(r, depth - 1), occur);
        }
        if (shoulds > 1 && r.nextInt(5) == 0) {
          builder.setMinimumNumberShouldMatch(1 + r.nextInt(shoulds));
        }
        return builder.build();
    }
  }

  private static Document randomDoc(Random r) {
    Document doc = new Document();
    for (String field : FIELDS) {
      if (r.nextInt(10) < 7) {
        StringBuilder text = new StringBuilder();
        for (int i = 1 + r.nextInt(3); i > 0; i--) {
          text.append(value(r)).append(' ');
        }
        doc.add(new TextField(field, text.toString(), Field.Store.NO));
      }
    }
    return doc;
  }

  private static TermWeightor randomWeightor(Random r) {
    long seed = r.nextLong();
    TermWeightor weights = t -> 0.05 + 5 * new Random(seed ^ t.hashCode()).nextDouble();
    return switch (r.nextInt(3)) {
      case 0 -> TermWeightor.DEFAULT;
      case 1 -> weights;
      default ->
          TermWeightor.of(weights, t -> 0.01 + 3 * new Random(~seed ^ t.hashCode()).nextDouble());
    };
  }

  private static Set<String> matchIds(MultiMatchingQueries<QueryMatch> matches, int doc) {
    Set<String> ids = new HashSet<>();
    for (QueryMatch match : matches.getMatches(doc)) {
      ids.add(match.getQueryId());
    }
    return ids;
  }

  public void testNotSlotsNeverDropMatches() throws IOException {
    Random r = random();
    for (int iteration = atLeast(5); iteration > 0; iteration--) {
      int maxNotSlots = 1 + r.nextInt(2);
      TermWeightor weightor = randomWeightor(r);
      List<CustomQueryHandler> handlers = new ArrayList<>();
      if (r.nextBoolean()) {
        handlers.add(new RegexpQueryHandler());
      }
      handlers.add(new OpaqueTermsQuery.Handler(r.nextBoolean()));
      int passes = r.nextInt(7);
      Presearcher presearcher =
          passes == 0
              ? new TermFilteredPresearcher(weightor, handlers, Set.of(), maxNotSlots)
              : new MultipassTermFilteredPresearcher(
                  passes, 0, weightor, handlers, Set.of(), maxNotSlots);
      String config =
          "passes=" + passes + " maxNotSlots=" + maxNotSlots + " handlers=" + handlers.size();

      try (Monitor oracle = new Monitor(ANALYZER, Presearcher.NO_FILTERING);
          Monitor monitor = new Monitor(ANALYZER, presearcher)) {
        List<MonitorQuery> queries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
          queries.add(new MonitorQuery(Integer.toString(i), randomQuery(r, 3)));
        }
        oracle.register(queries);
        monitor.register(queries);

        for (int batch = 0; batch < 20; batch++) {
          Document[] docs = new Document[1 + r.nextInt(4)];
          for (int i = 0; i < docs.length; i++) {
            docs[i] = randomDoc(r);
          }
          MultiMatchingQueries<QueryMatch> expected = oracle.match(docs, QueryMatch.SIMPLE_MATCHER);
          MultiMatchingQueries<QueryMatch> actual = monitor.match(docs, QueryMatch.SIMPLE_MATCHER);
          for (int i = 0; i < docs.length; i++) {
            Set<String> missing = matchIds(expected, i);
            missing.removeAll(matchIds(actual, i));
            if (!missing.isEmpty()) {
              StringBuilder message =
                  new StringBuilder(config + ": dropped matches for " + docs[i] + "\n");
              for (String id : missing) {
                message
                    .append(id)
                    .append(": ")
                    .append(queries.get(Integer.parseInt(id)).getQuery());
                message.append('\n');
              }
              fail(message.toString());
            }
          }
        }
      }
    }
  }

  public void testZeroNotSlotsSelectsLikeTheExistingConstructors() throws IOException {
    Random r = random();
    int passes = 1 + r.nextInt(6);
    TermWeightor weightor = randomWeightor(r);
    List<CustomQueryHandler> handlers = List.of(new RegexpQueryHandler());
    try (Monitor existing =
            new Monitor(
                ANALYZER,
                new MultipassTermFilteredPresearcher(passes, 0, weightor, handlers, Set.of()));
        Monitor zero =
            new Monitor(
                ANALYZER,
                new MultipassTermFilteredPresearcher(passes, 0, weightor, handlers, Set.of(), 0));
        Monitor existingSingle =
            new Monitor(ANALYZER, new TermFilteredPresearcher(weightor, handlers, Set.of()));
        Monitor zeroSingle =
            new Monitor(ANALYZER, new TermFilteredPresearcher(weightor, handlers, Set.of(), 0))) {
      List<MonitorQuery> queries = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        queries.add(new MonitorQuery(Integer.toString(i), randomQuery(r, 3)));
      }
      for (Monitor m : List.of(existing, zero, existingSingle, zeroSingle)) {
        m.register(queries);
      }
      for (int batch = 0; batch < 20; batch++) {
        Document[] docs = new Document[1 + r.nextInt(4)];
        for (int i = 0; i < docs.length; i++) {
          docs[i] = randomDoc(r);
        }
        assertEquals(
            existing.match(docs, QueryMatch.SIMPLE_MATCHER).getQueriesRun(),
            zero.match(docs, QueryMatch.SIMPLE_MATCHER).getQueriesRun());
        assertEquals(
            existingSingle.match(docs, QueryMatch.SIMPLE_MATCHER).getQueriesRun(),
            zeroSingle.match(docs, QueryMatch.SIMPLE_MATCHER).getQueriesRun());
      }
    }
  }
}
