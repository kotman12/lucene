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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.tests.util.LuceneTestCase;

public class TestTermWeightor extends LuceneTestCase {

  private static final Term RARE = new Term("f", "rare");
  private static final Term COMMON = new Term("f", "common");

  public void testDefaultNegatedWeightReadsWeightsAsSurprisal() {
    // weights as -ln p: the rare term is in 1% of documents, the common one in 60%
    Map<Term, Double> weights = Map.of(RARE, -Math.log(0.01), COMMON, -Math.log(0.6));
    TermWeightor weightor = weights::get;

    assertEquals(-Math.log(0.99), weightor.applyNegatedAsDouble(RARE), 1e-9);
    assertEquals(-Math.log(0.4), weightor.applyNegatedAsDouble(COMMON), 1e-9);
    assertTrue(weightor.applyNegatedAsDouble(COMMON) > weightor.applyNegatedAsDouble(RARE));
  }

  public void testDefaultNegatedWeightIsAlwaysPositive() {
    TermWeightor weightor = _ -> 1000;
    assertEquals(TermWeightor.MIN_NEGATED_WEIGHT, weightor.applyNegatedAsDouble(RARE), 0);
  }

  public void testDefaultWeightorRanksNotSlotsBelowTerms() {
    double minTermWeight = Double.MAX_VALUE;
    Set<Term> allTerms = new HashSet<>();
    for (int length = 1; length <= 40; length++) {
      Term term = new Term("f", "x".repeat(length));
      allTerms.add(term);
      minTermWeight = Math.min(minTermWeight, TermWeightor.DEFAULT.applyAsDouble(term));
      assertTrue(QueryTree.notSlot(Set.of(term), TermWeightor.DEFAULT).weight() < minTermWeight);
    }
    assertTrue(QueryTree.notSlot(allTerms, TermWeightor.DEFAULT).weight() < minTermWeight);
  }

  public void testFactorWeightorsScaleNegatedWeights() {
    TermWeightor field = TermWeightor.fieldWeightor(0.5, "f");
    assertEquals(0.5, field.applyNegatedAsDouble(RARE), 0);
    assertEquals(1, field.applyNegatedAsDouble(new Term("g", "x")), 0);

    TermWeightor term = TermWeightor.termWeightor(3, RARE.bytes());
    assertEquals(3, term.applyNegatedAsDouble(RARE), 0);
    assertEquals(1, term.applyNegatedAsDouble(COMMON), 0);

    TermWeightor termAndField = TermWeightor.termAndFieldWeightor(4, COMMON);
    assertEquals(4, termAndField.applyNegatedAsDouble(COMMON), 0);
    assertEquals(1, termAndField.applyNegatedAsDouble(RARE), 0);
  }

  public void testCombineMultipliesNegatedWeights() {
    TermWeightor base = TermWeightor.of(_ -> 2, _ -> 0.3);
    TermWeightor combined = TermWeightor.combine(base, TermWeightor.fieldWeightor(0.5, "f"));
    assertEquals(1, combined.applyAsDouble(RARE), 1e-9);
    assertEquals(0.15, combined.applyNegatedAsDouble(RARE), 1e-9);
    assertEquals(0.3, combined.applyNegatedAsDouble(new Term("g", "x")), 1e-9);
  }

  public void testOfRoutesBothWeightings() {
    TermWeightor weightor = TermWeightor.of(_ -> 7, _ -> 0.25);
    assertEquals(7, weightor.applyAsDouble(RARE), 0);
    assertEquals(0.25, weightor.applyNegatedAsDouble(RARE), 0);
  }
}
