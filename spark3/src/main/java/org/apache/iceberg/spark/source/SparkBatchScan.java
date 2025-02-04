/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.spark.source;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.apache.iceberg.CombinedScanTask;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.SnapshotSummary;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.hadoop.HadoopInputFile;
import org.apache.iceberg.hadoop.Util;
import org.apache.iceberg.hivelink.core.LegacyHiveTable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.orc.OrcRowFilterUtils;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.spark.SparkUtil;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.iceberg.util.TableScanUtil;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.RuntimeConfig;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.read.Batch;
import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.connector.read.Statistics;
import org.apache.spark.sql.connector.read.SupportsReportStatistics;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.apache.spark.sql.vectorized.ColumnarBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.iceberg.TableProperties.READ_ORC_IGNORE_FILE_FIELD_IDS;
import static org.apache.iceberg.TableProperties.READ_ORC_IGNORE_FILE_FIELD_IDS_DEFAULT;

abstract class SparkBatchScan implements Scan, Batch, SupportsReportStatistics {
  private static final Logger LOG = LoggerFactory.getLogger(SparkBatchScan.class);

  private final Table table;
  private final boolean caseSensitive;
  private final boolean localityPreferred;
  private final Schema expectedSchema;
  private final List<Expression> filterExpressions;
  private final Broadcast<FileIO> io;
  private final Broadcast<EncryptionManager> encryptionManager;
  private final boolean batchReadsEnabled;
  private final int batchSize;
  private final boolean readTimestampWithoutZone;

  // lazy variables
  private StructType readSchema = null;

  SparkBatchScan(Table table, Broadcast<FileIO> io, Broadcast<EncryptionManager> encryption,
                 boolean caseSensitive, Schema expectedSchema, List<Expression> filters,
                 CaseInsensitiveStringMap options) {
    this.table = table;
    this.io = io;
    this.encryptionManager = encryption;
    this.caseSensitive = caseSensitive;
    this.expectedSchema = expectedSchema;
    this.filterExpressions = filters != null ? filters : Collections.emptyList();
    this.localityPreferred = Spark3Util.isLocalityEnabled(io.value(), table.location(), options);
    this.batchReadsEnabled = Spark3Util.isVectorizationEnabled(table.properties(), options);
    this.batchSize = Spark3Util.batchSize(table.properties(), options);

    RuntimeConfig sessionConf = SparkSession.active().conf();
    this.readTimestampWithoutZone = SparkUtil.canHandleTimestampWithoutZone(options, sessionConf);
  }

  protected Table table() {
    return table;
  }

  protected boolean caseSensitive() {
    return caseSensitive;
  }

  protected Schema expectedSchema() {
    return expectedSchema;
  }

  protected List<Expression> filterExpressions() {
    return filterExpressions;
  }

  protected abstract List<CombinedScanTask> tasks();

  @Override
  public Batch toBatch() {
    return this;
  }

  @Override
  public StructType readSchema() {
    if (readSchema == null) {
      Preconditions.checkArgument(readTimestampWithoutZone || !SparkUtil.hasTimestampWithoutZone(expectedSchema),
          SparkUtil.TIMESTAMP_WITHOUT_TIMEZONE_ERROR);
      this.readSchema = SparkSchemaUtil.convert(expectedSchema);
    }
    return readSchema;
  }

  @Override
  public InputPartition[] planInputPartitions() {
    String tableSchemaString = SchemaParser.toJson(table.schema());
    String expectedSchemaString = SchemaParser.toJson(expectedSchema);
    String nameMappingString = table.properties().get(TableProperties.DEFAULT_NAME_MAPPING);
    boolean ignoreFileFieldIds = PropertyUtil.propertyAsBoolean(
        table.properties(),
        READ_ORC_IGNORE_FILE_FIELD_IDS,
        READ_ORC_IGNORE_FILE_FIELD_IDS_DEFAULT);

    List<CombinedScanTask> scanTasks = tasks();
    InputPartition[] readTasks = new InputPartition[scanTasks.size()];
    for (int i = 0; i < scanTasks.size(); i++) {
      readTasks[i] = new ReadTask(
          scanTasks.get(i), tableSchemaString, expectedSchemaString, nameMappingString, io, encryptionManager,
          caseSensitive, localityPreferred, ignoreFileFieldIds);
    }

    return readTasks;
  }

  @Override
  public PartitionReaderFactory createReaderFactory() {
    boolean allParquetFileScanTasks =
        tasks().stream()
            .allMatch(combinedScanTask -> !combinedScanTask.isDataTask() && combinedScanTask.files()
                .stream()
                .allMatch(fileScanTask -> fileScanTask.file().format().equals(
                    FileFormat.PARQUET)));

    boolean allOrcFileScanTasks =
        tasks().stream()
            .allMatch(combinedScanTask -> !combinedScanTask.isDataTask() && combinedScanTask.files()
                .stream()
                .allMatch(fileScanTask -> fileScanTask.file().format().equals(
                    FileFormat.ORC)));

    boolean hasNoRowFilters =
        tasks().stream()
            .allMatch(combinedScanTask -> !combinedScanTask.isDataTask() && combinedScanTask.files()
                .stream()
                .allMatch(fileScanTask -> OrcRowFilterUtils.rowFilterFromTask(fileScanTask) == null));

    boolean atLeastOneColumn = expectedSchema.columns().size() > 0;

    boolean onlyPrimitives = expectedSchema.columns().stream().allMatch(c -> c.type().isPrimitiveType());

    boolean hasNoDeleteFiles = tasks().stream().noneMatch(TableScanUtil::hasDeletes);

    boolean hasTimestampWithoutZone = SparkUtil.hasTimestampWithoutZone(expectedSchema);

    boolean readUsingBatch = batchReadsEnabled && hasNoDeleteFiles && ((allOrcFileScanTasks && hasNoRowFilters) ||
        (allParquetFileScanTasks && atLeastOneColumn && onlyPrimitives && !hasTimestampWithoutZone));

    return new ReaderFactory(readUsingBatch ? batchSize : 0);
  }

  @Override
  public Statistics estimateStatistics() {
    if (table instanceof LegacyHiveTable) {
      // We currently don't have reliable stats for Hive tables
      return EMPTY_STATS;
    }

    // its a fresh table, no data
    if (table.currentSnapshot() == null) {
      return new Stats(0L, 0L);
    }

    // estimate stats using snapshot summary only for partitioned tables (metadata tables are unpartitioned)
    if (!table.spec().isUnpartitioned() && filterExpressions.isEmpty()) {
      LOG.debug("using table metadata to estimate table statistics");
      long totalRecords = PropertyUtil.propertyAsLong(table.currentSnapshot().summary(),
          SnapshotSummary.TOTAL_RECORDS_PROP, Long.MAX_VALUE);
      Schema projectedSchema = expectedSchema != null ? expectedSchema : table.schema();
      return new Stats(
          SparkSchemaUtil.estimateSize(SparkSchemaUtil.convert(projectedSchema), totalRecords),
          totalRecords);
    }

    long sizeInBytes = 0L;
    long numRows = 0L;

    for (CombinedScanTask task : tasks()) {
      for (FileScanTask file : task.files()) {
        sizeInBytes += file.length();
        numRows += file.file().recordCount();
      }
    }

    return new Stats(sizeInBytes, numRows);
  }

  private static final Statistics EMPTY_STATS = new Statistics() {
    @Override
    public OptionalLong sizeInBytes() {
      return OptionalLong.empty();
    }

    @Override
    public OptionalLong numRows() {
      return OptionalLong.empty();
    }
  };

  @Override
  public String description() {
    String filters = filterExpressions.stream().map(Spark3Util::describe).collect(Collectors.joining(", "));
    return String.format("%s [filters=%s]", table, filters);
  }

  private static class ReaderFactory implements PartitionReaderFactory {
    private final int batchSize;

    private ReaderFactory(int batchSize) {
      this.batchSize = batchSize;
    }

    @Override
    public PartitionReader<InternalRow> createReader(InputPartition partition) {
      if (partition instanceof ReadTask) {
        return new RowReader((ReadTask) partition);
      } else {
        throw new UnsupportedOperationException("Incorrect input partition type: " + partition);
      }
    }

    @Override
    public PartitionReader<ColumnarBatch> createColumnarReader(InputPartition partition) {
      if (partition instanceof ReadTask) {
        return new BatchReader((ReadTask) partition, batchSize);
      } else {
        throw new UnsupportedOperationException("Incorrect input partition type: " + partition);
      }
    }

    @Override
    public boolean supportColumnarReads(InputPartition partition) {
      return batchSize > 1;
    }
  }

  private static class RowReader extends RowDataReader implements PartitionReader<InternalRow> {
    RowReader(ReadTask task) {
      super(task.task, task.tableSchema(), task.expectedSchema(), task.nameMappingString, task.io(), task.encryption(),
          task.isCaseSensitive(), task.getIgnoreFileFieldIds());
    }
  }

  private static class BatchReader extends BatchDataReader implements PartitionReader<ColumnarBatch> {
    BatchReader(ReadTask task, int batchSize) {
      super(task.task, task.expectedSchema(), task.nameMappingString, task.io(), task.encryption(),
          task.isCaseSensitive(), batchSize, task.getIgnoreFileFieldIds());
    }
  }

  private static class ReadTask implements InputPartition, Serializable {
    private final CombinedScanTask task;
    private final String tableSchemaString;
    private final String expectedSchemaString;
    private final String nameMappingString;
    private final Broadcast<FileIO> io;
    private final Broadcast<EncryptionManager> encryptionManager;
    private final boolean caseSensitive;
    private final boolean ignoreFileFieldIds;

    private transient Schema tableSchema = null;
    private transient Schema expectedSchema = null;
    private transient String[] preferredLocations = null;

    ReadTask(CombinedScanTask task, String tableSchemaString, String expectedSchemaString, String nameMappingString,
             Broadcast<FileIO> io, Broadcast<EncryptionManager> encryptionManager, boolean caseSensitive,
             boolean localityPreferred, boolean ignoreFileFieldIds) {
      this.task = task;
      this.tableSchemaString = tableSchemaString;
      this.expectedSchemaString = expectedSchemaString;
      this.nameMappingString = nameMappingString;
      this.io = io;
      this.encryptionManager = encryptionManager;
      this.caseSensitive = caseSensitive;
      if (localityPreferred) {
        this.preferredLocations = Util.blockLocations(io.value(), task);
      } else {
        this.preferredLocations = HadoopInputFile.NO_LOCATION_PREFERENCE;
      }
      this.ignoreFileFieldIds = ignoreFileFieldIds;
    }

    @Override
    public String[] preferredLocations() {
      return preferredLocations;
    }

    public Collection<FileScanTask> files() {
      return task.files();
    }

    public FileIO io() {
      return io.value();
    }

    public EncryptionManager encryption() {
      return encryptionManager.value();
    }

    public boolean isCaseSensitive() {
      return caseSensitive;
    }

    private Schema tableSchema() {
      if (tableSchema == null) {
        this.tableSchema = SchemaParser.fromJson(tableSchemaString);
      }
      return tableSchema;
    }

    private Schema expectedSchema() {
      if (expectedSchema == null) {
        this.expectedSchema = SchemaParser.fromJson(expectedSchemaString);
      }
      return expectedSchema;
    }

    public boolean getIgnoreFileFieldIds() {
      return ignoreFileFieldIds;
    }
  }
}
