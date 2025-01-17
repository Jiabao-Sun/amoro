/*
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

package com.netease.arctic.flink.lookup;

import static com.netease.arctic.flink.lookup.LookupMetrics.GROUP_NAME_LOOKUP;
import static com.netease.arctic.flink.lookup.LookupMetrics.LOADING_TIME_MS;
import static com.netease.arctic.flink.table.descriptors.ArcticValidator.LOOKUP_RELOADING_INTERVAL;
import static com.netease.arctic.flink.util.ArcticUtils.loadArcticTable;
import static org.apache.flink.util.Preconditions.checkArgument;

import com.netease.arctic.flink.read.MixedIncrementalLoader;
import com.netease.arctic.flink.read.hybrid.enumerator.MergeOnReadIncrementalPlanner;
import com.netease.arctic.flink.read.hybrid.reader.DataIteratorReaderFunction;
import com.netease.arctic.flink.table.ArcticTableLoader;
import com.netease.arctic.hive.io.reader.AbstractAdaptHiveKeyedDataReader;
import com.netease.arctic.table.ArcticTable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.concurrent.ExecutorThreadFactory;
import org.apache.iceberg.Schema;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.io.CloseableIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** This is a basic lookup function for an arctic table. */
public class BasicLookupFunction<T> implements Serializable {
  private static final Logger LOG = LoggerFactory.getLogger(BasicLookupFunction.class);
  private static final long serialVersionUID = 1671720424494168710L;
  private ArcticTable arcticTable;
  private KVTable<T> kvTable;
  private final List<String> joinKeys;
  private final Schema projectSchema;
  private final List<Expression> filters;
  private final ArcticTableLoader loader;
  private long nextLoadTime = Long.MIN_VALUE;
  private final long reloadIntervalSeconds;
  private MixedIncrementalLoader<T> incrementalLoader;
  private final Configuration config;
  private transient AtomicLong lookupLoadingTimeMs;
  private final Predicate<T> predicate;
  private final TableFactory<T> kvTableFactory;
  private final AbstractAdaptHiveKeyedDataReader<T> flinkArcticMORDataReader;
  private final DataIteratorReaderFunction<T> readerFunction;

  private transient ScheduledExecutorService executor;
  private final AtomicReference<Throwable> failureThrowable = new AtomicReference<>();

  public BasicLookupFunction(
      TableFactory<T> tableFactory,
      ArcticTable arcticTable,
      List<String> joinKeys,
      Schema projectSchema,
      List<Expression> filters,
      ArcticTableLoader tableLoader,
      Configuration config,
      Predicate<T> predicate,
      AbstractAdaptHiveKeyedDataReader<T> flinkArcticMORDataReader,
      DataIteratorReaderFunction<T> readerFunction) {
    checkArgument(
        arcticTable.isKeyedTable(),
        String.format(
            "Only keyed arctic table support lookup join, this table [%s] is an unkeyed table.",
            arcticTable.name()));
    Preconditions.checkNotNull(tableFactory, "kvTableFactory cannot be null");
    this.kvTableFactory = tableFactory;
    this.joinKeys = joinKeys;
    this.projectSchema = projectSchema;
    this.filters = filters;
    this.loader = tableLoader;
    this.config = config;
    this.reloadIntervalSeconds = config.get(LOOKUP_RELOADING_INTERVAL).getSeconds();
    this.predicate = predicate;
    this.flinkArcticMORDataReader = flinkArcticMORDataReader;
    this.readerFunction = readerFunction;
  }

  /**
   * Open the lookup function, e.g.: create {@link KVTable} kvTable, and load data.
   *
   * @throws IOException If serialize or deserialize failed
   */
  public void open(FunctionContext context) throws IOException {
    init(context);
    start();
  }

  /**
   * Initialize the arcticTable, kvTable and incrementalLoader.
   *
   * @param context
   */
  public void init(FunctionContext context) {
    LOG.info("lookup function row data predicate: {}.", predicate);
    MetricGroup metricGroup = context.getMetricGroup().addGroup(GROUP_NAME_LOOKUP);
    if (arcticTable == null) {
      arcticTable = loadArcticTable(loader).asKeyedTable();
    }
    arcticTable.refresh();

    lookupLoadingTimeMs = new AtomicLong();
    metricGroup.gauge(LOADING_TIME_MS, () -> lookupLoadingTimeMs.get());

    LOG.info("projected schema {}.\n table schema {}.", projectSchema, arcticTable.schema());
    kvTable =
        kvTableFactory.create(
            new RowDataStateFactory(generateRocksDBPath(context, arcticTable.name()), metricGroup),
            arcticTable.asKeyedTable().primaryKeySpec().fieldNames(),
            joinKeys,
            projectSchema,
            config,
            predicate);
    kvTable.open();

    this.incrementalLoader =
        new MixedIncrementalLoader<>(
            new MergeOnReadIncrementalPlanner(loader),
            flinkArcticMORDataReader,
            readerFunction,
            filters);
  }

  public void start() {
    // Keep the first-time synchronized loading to avoid a mass of null-match records during
    // initialization
    checkAndLoad();

    this.executor =
        Executors.newScheduledThreadPool(
            1, new ExecutorThreadFactory("Arctic-lookup-scheduled-loader"));
    this.executor.scheduleWithFixedDelay(
        () -> {
          try {
            checkAndLoad();
          } catch (Exception e) {
            // fail the lookup and skip the rest of the items
            // if the failure handler decides to throw an exception
            failureThrowable.compareAndSet(null, e);
          }
        },
        0,
        reloadIntervalSeconds,
        TimeUnit.MILLISECONDS);
  }

  public List<T> lookup(Object... values) {
    checkErrorAndRethrow();
    try {
      RowData lookupKey = GenericRowData.of(values);
      return kvTable.get(lookupKey);
    } catch (Exception e) {
      throw new FlinkRuntimeException(e);
    }
  }

  /**
   * Check whether it is time to periodically load data to kvTable. Support to use {@link
   * Expression} filters to filter the data.
   */
  private synchronized void checkAndLoad() {
    if (nextLoadTime > System.currentTimeMillis()) {
      return;
    }
    nextLoadTime = System.currentTimeMillis() + 1000 * reloadIntervalSeconds;

    long batchStart = System.currentTimeMillis();
    while (incrementalLoader.hasNext()) {
      long start = System.currentTimeMillis();
      arcticTable
          .io()
          .doAs(
              () -> {
                try (CloseableIterator<T> iterator = incrementalLoader.next()) {
                  if (kvTable.initialized()) {
                    kvTable.upsert(iterator);
                  } else {
                    LOG.info(
                        "This table {} is still under initialization progress.",
                        arcticTable.name());
                    kvTable.initialize(iterator);
                  }
                }
                return null;
              });
      LOG.info("Split task fetched, cost {}ms.", System.currentTimeMillis() - start);
    }
    if (!kvTable.initialized()) {
      kvTable.waitInitializationCompleted();
    }
    lookupLoadingTimeMs.set(System.currentTimeMillis() - batchStart);

    LOG.info(
        "{} table lookup loading, these batch tasks completed, cost {}ms.",
        arcticTable.name(),
        lookupLoadingTimeMs.get());
  }

  public KVTable<T> getKVTable() {
    return kvTable;
  }

  public void close() throws Exception {
    if (kvTable != null) {
      kvTable.close();
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  private void checkErrorAndRethrow() {
    Throwable cause = failureThrowable.get();
    if (cause != null) {
      throw new RuntimeException("An error occurred in ArcticLookupFunction.", cause);
    }
  }

  private String generateRocksDBPath(FunctionContext context, String tableName) {
    String tmpPath = getTmpDirectoryFromTMContainer(context);
    File db = new File(tmpPath, tableName + "-lookup-" + UUID.randomUUID());
    return db.toString();
  }

  private static String getTmpDirectoryFromTMContainer(FunctionContext context) {
    try {
      Field field = context.getClass().getDeclaredField("context");
      field.setAccessible(true);
      StreamingRuntimeContext runtimeContext = (StreamingRuntimeContext) field.get(context);
      String[] tmpDirectories = runtimeContext.getTaskManagerRuntimeInfo().getTmpDirectories();
      return tmpDirectories[ThreadLocalRandom.current().nextInt(tmpDirectories.length)];
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
