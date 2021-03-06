/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.NavigableSet;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFileDataBlockEncoder;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionContext;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionProgress;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;

/**
 * Interface for objects that hold a column family in a Region. Its a memstore and a set of zero or
 * more StoreFiles, which stretch backwards over time.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public interface Store extends HeapSize, StoreConfigInformation {

  /* The default priority for user-specified compaction requests.
   * The user gets top priority unless we have blocking compactions. (Pri <= 0)
   */
  public static final int PRIORITY_USER = 1;
  public static final int NO_PRIORITY = Integer.MIN_VALUE;

  // General Accessors
  public KeyValue.KVComparator getComparator();

  public Collection<StoreFile> getStorefiles();

  /**
   * Close all the readers We don't need to worry about subsequent requests because the HRegion
   * holds a write lock that will prevent any more reads or writes.
   * @return the {@link StoreFile StoreFiles} that were previously being used.
   * @throws IOException on failure
   */
  public Collection<StoreFile> close() throws IOException;

  /**
   * Return a scanner for both the memstore and the HStore files. Assumes we are not in a
   * compaction.
   * @param scan Scan to apply when scanning the stores
   * @param targetCols columns to scan
   * @return a scanner over the current key values
   * @throws IOException on failure
   */
  public KeyValueScanner getScanner(Scan scan, final NavigableSet<byte[]> targetCols)
      throws IOException;

  /**
   * Get all scanners with no filtering based on TTL (that happens further down
   * the line).
   * @param cacheBlocks
   * @param isGet
   * @param isCompaction
   * @param matcher
   * @param startRow
   * @param stopRow
   * @return all scanners for this store
   */
  public List<KeyValueScanner> getScanners(boolean cacheBlocks,
      boolean isGet, boolean isCompaction, ScanQueryMatcher matcher, byte[] startRow,
      byte[] stopRow) throws IOException;

  public ScanInfo getScanInfo();

  /**
   * Adds or replaces the specified KeyValues.
   * <p>
   * For each KeyValue specified, if a cell with the same row, family, and qualifier exists in
   * MemStore, it will be replaced. Otherwise, it will just be inserted to MemStore.
   * <p>
   * This operation is atomic on each KeyValue (row/family/qualifier) but not necessarily atomic
   * across all of them.
   * @param cells
   * @param readpoint readpoint below which we can safely remove duplicate KVs 
   * @return memstore size delta
   * @throws IOException
   */
  public long upsert(Iterable<? extends Cell> cells, long readpoint) throws IOException;

  /**
   * Adds a value to the memstore
   * @param kv
   * @return memstore size delta
   */
  public long add(KeyValue kv);

  /**
   * Removes a kv from the memstore. The KeyValue is removed only if its key & memstoreTS match the
   * key & memstoreTS value of the kv parameter.
   * @param kv
   */
  public void rollback(final KeyValue kv);

  /**
   * Find the key that matches <i>row</i> exactly, or the one that immediately precedes it. WARNING:
   * Only use this method on a table where writes occur with strictly increasing timestamps. This
   * method assumes this pattern of writes in order to make it reasonably performant. Also our
   * search is dependent on the axiom that deletes are for cells that are in the container that
   * follows whether a memstore snapshot or a storefile, not for the current container: i.e. we'll
   * see deletes before we come across cells we are to delete. Presumption is that the
   * memstore#kvset is processed before memstore#snapshot and so on.
   * @param row The row key of the targeted row.
   * @return Found keyvalue or null if none found.
   * @throws IOException
   */
  public KeyValue getRowKeyAtOrBefore(final byte[] row) throws IOException;

  public FileSystem getFileSystem();

  /*
   * @param maxKeyCount
   * @param compression Compression algorithm to use
   * @param isCompaction whether we are creating a new file in a compaction
   * @param includeMVCCReadpoint whether we should out the MVCC readpoint
   * @return Writer for a new StoreFile in the tmp dir.
   */
  public StoreFile.Writer createWriterInTmp(long maxKeyCount, Compression.Algorithm compression,
      boolean isCompaction, boolean includeMVCCReadpoint) throws IOException;

  // Compaction oriented methods

  public boolean throttleCompaction(long compactionSize);

  /**
   * getter for CompactionProgress object
   * @return CompactionProgress object; can be null
   */
  public CompactionProgress getCompactionProgress();

  public CompactionContext requestCompaction() throws IOException;

  public CompactionContext requestCompaction(int priority, CompactionRequest baseRequest)
      throws IOException;

  public void cancelRequestedCompaction(CompactionContext compaction);

  public List<StoreFile> compact(CompactionContext compaction) throws IOException;

  /**
   * @return true if we should run a major compaction.
   */
  public boolean isMajorCompaction() throws IOException;

  public void triggerMajorCompaction();

  /**
   * See if there's too much store files in this store
   * @return true if number of store files is greater than the number defined in minFilesToCompact
   */
  public boolean needsCompaction();

  public int getCompactPriority();

  public StoreFlusher getStoreFlusher(long cacheFlushId);

  // Split oriented methods

  public boolean canSplit();

  /**
   * Determines if Store should be split
   * @return byte[] if store should be split, null otherwise.
   */
  public byte[] getSplitPoint();

  // Bulk Load methods

  /**
   * This throws a WrongRegionException if the HFile does not fit in this region, or an
   * InvalidHFileException if the HFile is not valid.
   */
  public void assertBulkLoadHFileOk(Path srcPath) throws IOException;

  /**
   * This method should only be called from HRegion. It is assumed that the ranges of values in the
   * HFile fit within the stores assigned region. (assertBulkLoadHFileOk checks this)
   * 
   * @param srcPathStr
   * @param sequenceId sequence Id associated with the HFile
   */
  public void bulkLoadHFile(String srcPathStr, long sequenceId) throws IOException;

  // General accessors into the state of the store
  // TODO abstract some of this out into a metrics class

  /**
   * @return <tt>true</tt> if the store has any underlying reference files to older HFiles
   */
  public boolean hasReferences();

  /**
   * @return The size of this store's memstore, in bytes
   */
  public long getMemStoreSize();

  public HColumnDescriptor getFamily();

  /**
   * @return The maximum memstoreTS in all store files.
   */
  public long getMaxMemstoreTS();

  /**
   * @return the data block encoder
   */
  public HFileDataBlockEncoder getDataBlockEncoder();

  /** @return aggregate size of all HStores used in the last compaction */
  public long getLastCompactSize();

  /** @return aggregate size of HStore */
  public long getSize();

  /**
   * @return Count of store files
   */
  public int getStorefilesCount();

  /**
   * @return The size of the store files, in bytes, uncompressed.
   */
  public long getStoreSizeUncompressed();

  /**
   * @return The size of the store files, in bytes.
   */
  public long getStorefilesSize();

  /**
   * @return The size of the store file indexes, in bytes.
   */
  public long getStorefilesIndexSize();

  /**
   * Returns the total size of all index blocks in the data block indexes, including the root level,
   * intermediate levels, and the leaf level for multi-level indexes, or just the root level for
   * single-level indexes.
   * @return the total size of block indexes in the store
   */
  public long getTotalStaticIndexSize();

  /**
   * Returns the total byte size of all Bloom filter bit arrays. For compound Bloom filters even the
   * Bloom blocks currently not loaded into the block cache are counted.
   * @return the total size of all Bloom filters in the store
   */
  public long getTotalStaticBloomSize();

  // Test-helper methods

  /**
   * Used for tests.
   * @return cache configuration for this Store.
   */
  public CacheConfig getCacheConfig();

  /**
   * @return the parent region info hosting this store
   */
  public HRegionInfo getRegionInfo();

  public RegionCoprocessorHost getCoprocessorHost();

  public boolean areWritesEnabled();

  /**
   * @return The smallest mvcc readPoint across all the scanners in this
   * region. Writes older than this readPoint, are included  in every
   * read operation.
   */
  public long getSmallestReadPoint();

  public String getColumnFamilyName();

  public String getTableName();

  /*
   * @param o Observer who wants to know about changes in set of Readers
   */
  public void addChangedReaderObserver(ChangedReadersObserver o);

  /*
   * @param o Observer no longer interested in changes in set of Readers.
   */
  public void deleteChangedReaderObserver(ChangedReadersObserver o);

  /**
   * @return Whether this store has too many store files.
   */
  public boolean hasTooManyStoreFiles();
}