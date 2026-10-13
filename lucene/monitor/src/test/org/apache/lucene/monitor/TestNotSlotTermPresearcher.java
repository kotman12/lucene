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
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;

public class TestNotSlotTermPresearcher extends NotSlotPresearcherTestBase {

  @Override
  protected Presearcher createPresearcher(int maxNotSlots, List<CustomQueryHandler> handlers) {
    return new TermFilteredPresearcher(TermWeightor.DEFAULT, handlers, Set.of(), maxNotSlots);
  }

  public void testSinglePassPrefersPositiveTerms() throws IOException {
    // the positive term outweighs the NotSlot, so the single pass indexes x:abc
    assertRunAndMatched(1, 0, run("+x:abc -f:t", doc("x", "abc", "f", "t")));
    assertRunAndMatched(0, 0, run("+x:abc -f:t", doc("f", "t")));
  }

  public void testNegatedTermsAreNotIndexedAsPositives() throws IOException {
    // a document containing only the negated term must not select the query
    assertRunAndMatched(0, 0, run("*:* -f:t", doc("f", "t")));
  }

  public void testQueryBuildersMustSupportNotSlots() throws IOException {
    TermFilteredPresearcher custom =
        new TermFilteredPresearcher(TermWeightor.DEFAULT, List.of(), Set.of(), 1) {
          @Override
          protected DocumentQueryBuilder getQueryBuilder() {
            return new DocumentQueryBuilder() {
              final BooleanQuery.Builder builder = new BooleanQuery.Builder();

              @Override
              public void addTerm(String field, BytesRef term) {}

              @Override
              public Query build() {
                return builder.build();
              }
            };
          }
        };
    expectThrows(IllegalStateException.class, () -> run(custom, "*:* -f:t", doc("f", "t")));
  }

  public void testNegativeMaxNotSlotsIsRejected() {
    expectThrows(
        IllegalArgumentException.class,
        () -> new TermFilteredPresearcher(TermWeightor.DEFAULT, List.of(), Set.of(), -1));
  }
}
