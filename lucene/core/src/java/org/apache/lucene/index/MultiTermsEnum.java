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
package org.apache.lucene.index;

import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.PriorityQueue;

/**
 * Exposes {@link TermsEnum} API, merged from {@link TermsEnum} API of sub-segments. This does a
 * merge sort, by term text, of the sub-readers.
 *
 * @lucene.experimental
 */
public final class MultiTermsEnum extends BaseTermsEnum {

  private final TermMergeQueue queue;
  // all of our subs (one per sub-reader)
  private final TermsEnumWithSlice[] subs;
  // current subs that have at least one term for this field
  private final TermsEnumWithSlice[] currentSubs;
  private final TermsEnumWithSlice[] top;
  private final MultiPostingsEnum.EnumWithSlice[] subDocs;

  private BytesRef lastSeek;
  private boolean lastSeekExact;
  private final BytesRefBuilder lastSeekScratch = new BytesRefBuilder();

  private int numTop;
  private int numSubs;
  private BytesRef current;

  /** Returns how many sub-reader slices contain the current term. @see #getMatchArray */
  public int getMatchCount() {
    return numTop;
  }

  /** Returns sub-reader slices positioned to the current term. */
  public TermsEnumWithSlice[] getMatchArray() {
    return top;
  }

  /**
   * Sole constructor.
   *
   * @param slices Which sub-reader slices we should merge.
   */
  public MultiTermsEnum(ReaderSlice[] slices) {
    queue = new TermMergeQueue(slices.length);
    top = new TermsEnumWithSlice[slices.length];
    subs = new TermsEnumWithSlice[slices.length];
    subDocs = new MultiPostingsEnum.EnumWithSlice[slices.length];
    for (int i = 0; i < slices.length; i++) {
      subs[i] = new TermsEnumWithSlice(i, slices[i]);
      subDocs[i] = new MultiPostingsEnum.EnumWithSlice();
      subDocs[i].slice = slices[i];
    }
    currentSubs = new TermsEnumWithSlice[slices.length];
  }

  @Override
  public BytesRef term() {
    return current;
  }

  /**
   * The terms array must be newly created TermsEnum, ie {@link TermsEnum#next} has not yet been
   * called.
   */
  public TermsEnum reset(TermsEnumIndex[] termsEnumsIndex) throws IOException {
    assert termsEnumsIndex.length <= top.length;
    numSubs = 0;
    numTop = 0;
    queue.clear();
    for (int i = 0; i < termsEnumsIndex.length; i++) {

      final TermsEnumIndex termsEnumIndex = termsEnumsIndex[i];
      assert termsEnumIndex != null;

      final BytesRef term = termsEnumIndex.next();
      if (term != null) {
        final TermsEnumWithSlice entry = subs[termsEnumIndex.subIndex];
        entry.reset(termsEnumIndex);
        queue.add(entry);
        currentSubs[numSubs++] = entry;
      } else {
        // field has no terms
      }
    }

    if (queue.size() == 0) {
      return TermsEnum.EMPTY;
    } else {
      return this;
    }
  }

  @Override
  public boolean seekExact(BytesRef term) throws IOException {
    queue.clear();
    numTop = 0;

    boolean seekOpt = false;
    if (lastSeek != null && lastSeek.compareTo(term) <= 0) {
      seekOpt = true;
    }

    lastSeek = null;
    lastSeekExact = true;

    for (int i = 0; i < numSubs; i++) {
      final boolean status;
      // LUCENE-2130: if we had just seek'd already, prior
      // to this seek, and the new seek term is after the
      // previous one, don't try to re-seek this sub if its
      // current term is already beyond this new seek term.
      // Doing so is a waste because this sub will simply
      // seek to the same spot.
      if (seekOpt) {
        final BytesRef curTerm = currentSubs[i].term();
        if (curTerm != null) {
          final int cmp = term.compareTo(curTerm);
          if (cmp == 0) {
            status = true;
          } else if (cmp < 0) {
            status = false;
          } else {
            status = currentSubs[i].seekExact(term);
          }
        } else {
          status = false;
        }
      } else {
        status = currentSubs[i].seekExact(term);
      }

      if (status) {
        top[numTop++] = currentSubs[i];
        current = currentSubs[i].term();
        assert term.equals(currentSubs[i].term());
      }
    }

    // if at least one sub had exact match to the requested
    // term then we found match
    return numTop > 0;
  }

  @Override
  public SeekStatus seekCeil(BytesRef term) throws IOException {
    queue.clear();
    numTop = 0;
    lastSeekExact = false;

    boolean seekOpt = false;
    if (lastSeek != null && lastSeek.compareTo(term) <= 0) {
      seekOpt = true;
    }

    lastSeekScratch.copyBytes(term);
    lastSeek = lastSeekScratch.get();

    for (int i = 0; i < numSubs; i++) {
      final SeekStatus status;
      // LUCENE-2130: if we had just seek'd already, prior
      // to this seek, and the new seek term is after the
      // previous one, don't try to re-seek this sub if its
      // current term is already beyond this new seek term.
      // Doing so is a waste because this sub will simply
      // seek to the same spot.
      if (seekOpt) {
        final BytesRef curTerm = currentSubs[i].term();
        if (curTerm != null) {
          final int cmp = term.compareTo(curTerm);
          if (cmp == 0) {
            status = SeekStatus.FOUND;
          } else if (cmp < 0) {
            status = SeekStatus.NOT_FOUND;
          } else {
            status = currentSubs[i].seekCeil(term);
          }
        } else {
          status = SeekStatus.END;
        }
      } else {
        status = currentSubs[i].seekCeil(term);
      }

      if (status == SeekStatus.FOUND) {
        top[numTop++] = currentSubs[i];
        current = currentSubs[i].term();
        queue.add(currentSubs[i]);
      } else {
        if (status == SeekStatus.NOT_FOUND) {
          assert currentSubs[i].term() != null;
          queue.add(currentSubs[i]);
        } else {
          assert status == SeekStatus.END;
        }
      }
    }

    if (numTop > 0) {
      // at least one sub had exact match to the requested term
      return SeekStatus.FOUND;
    } else if (queue.size() > 0) {
      // no sub had exact match, but at least one sub found
      // a term after the requested term -- advance to that
      // next term:
      pullTop();
      return SeekStatus.NOT_FOUND;
    } else {
      return SeekStatus.END;
    }
  }

  @Override
  public void seekExact(long ord) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long ord() {
    throw new UnsupportedOperationException();
  }

  private void pullTop() {
    // extract all subs from the queue that have the same
    // top term
    assert numTop == 0;
    numTop = queue.fillTop(top);
    current = top[0].term();
  }

  private void pushTop() throws IOException {
    // call next() on each top, and reorder queue
    for (int i = 0; i < numTop; i++) {
      TermsEnumWithSlice top = queue.top();
      if (top.next() == null) {
        queue.pop();
      } else {
        queue.updateTop();
      }
    }
    numTop = 0;
  }

  @Override
  public BytesRef next() throws IOException {
    if (lastSeekExact) {
      // Must seekCeil at this point, so those subs that
      // didn't have the term can find the following term.
      // NOTE: we could save some CPU by only seekCeil the
      // subs that didn't match the last exact seek... but
      // most impls short-circuit if you seekCeil to term
      // they are already on.
      final SeekStatus status = seekCeil(current);
      assert status == SeekStatus.FOUND;
      lastSeekExact = false;
    }
    lastSeek = null;

    // restore queue
    pushTop();

    // gather equal top fields
    if (queue.size() > 0) {
      // TODO: we could maybe defer this somewhat costly operation until one of the APIs that
      // needs to see the top is invoked (docFreq, postings, etc.)
      pullTop();
    } else {
      current = null;
    }

    return current;
  }

  @Override
  public int docFreq() throws IOException {
    int sum = 0;
    for (int i = 0; i < numTop; i++) {
      sum += top[i].termsEnum.docFreq();
    }
    return sum;
  }

  @Override
  public long totalTermFreq() throws IOException {
    long sum = 0;
    for (int i = 0; i < numTop; i++) {
      final long v = top[i].termsEnum.totalTermFreq();
      assert v != -1;
      sum += v;
    }
    return sum;
  }

  @Override
  public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
    MultiPostingsEnum docsEnum;

    // Can only reuse if incoming enum is also a MultiDocsEnum
    if (reuse != null && reuse instanceof MultiPostingsEnum) {
      docsEnum = (MultiPostingsEnum) reuse;
      // ... and was previously created w/ this MultiTermsEnum:
      if (!docsEnum.canReuse(this)) {
        docsEnum = new MultiPostingsEnum(this, subs.length);
      }
    } else {
      docsEnum = new MultiPostingsEnum(this, subs.length);
    }

    int upto = 0;

    ArrayUtil.timSort(top, 0, numTop, (o1, o2) -> o1.subIndex - o2.subIndex);

    for (int i = 0; i < numTop; i++) {

      final TermsEnumWithSlice entry = top[i];

      assert entry.subIndex < docsEnum.subPostingsEnums.length
          : entry.subIndex + " vs " + docsEnum.subPostingsEnums.length + "; " + subs.length;
      final PostingsEnum subPostingsEnum =
          entry.termsEnum.postings(docsEnum.subPostingsEnums[entry.subIndex], flags);
      assert subPostingsEnum != null;
      docsEnum.subPostingsEnums[entry.subIndex] = subPostingsEnum;
      subDocs[upto].postingsEnum = subPostingsEnum;
      subDocs[upto].slice = entry.subSlice;
      upto++;
    }

    return docsEnum.reset(subDocs, upto);
  }

  @Override
  public ImpactsEnum impacts(int flags) throws IOException {
    // implemented to not fail CheckIndex, but you shouldn't be using impacts on a slow reader
    return new SlowImpactsEnum(postings(null, flags));
  }

  static final class TermsEnumWithSlice extends TermsEnumIndex {
    private final ReaderSlice subSlice;

    public TermsEnumWithSlice(int index, ReaderSlice subSlice) {
      super(null, index);
      this.subSlice = subSlice;
      assert subSlice.length() >= 0 : "length=" + subSlice.length();
    }

    @Override
    public String toString() {
      return subSlice.toString() + ":" + super.toString();
    }
  }

  private static final class TermMergeQueue extends PriorityQueue<TermsEnumWithSlice> {

    final int[] stack;

    TermMergeQueue(int size) {
      super(size);
      this.stack = new int[size];
    }

    @Override
    protected boolean lessThan(TermsEnumWithSlice termsA, TermsEnumWithSlice termsB) {
      return termsA.compareTermTo(termsB) < 0;
    }

    /**
     * Add the {@link #top()} slice as well as all slices that are positioned on the same term to
     * {@code tops} and return how many of them there are.
     */
    int fillTop(TermsEnumWithSlice[] tops) {
      final int size = size();
      if (size == 0) {
        return 0;
      }
      tops[0] = top();
      int numTop = 1;
      stack[0] = 1;
      int stackLen = 1;

      while (stackLen != 0) {
        final int index = stack[--stackLen];
        final int leftChild = index << 1;
        for (int child = leftChild, end = Math.min(size, leftChild + 1); child <= end; ++child) {
          TermsEnumWithSlice te = (TermsEnumWithSlice) getHeapArray()[child];
          if (te.compareTermTo(tops[0]) == 0) {
            tops[numTop++] = te;
            stack[stackLen++] = child;
          }
        }
      }
      return numTop;
    }
  }

  @Override
  public String toString() {
    return "MultiTermsEnum(" + Arrays.toString(subs) + ")";
  }
}
