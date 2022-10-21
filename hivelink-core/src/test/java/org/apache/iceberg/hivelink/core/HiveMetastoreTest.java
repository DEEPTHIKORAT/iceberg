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

package org.apache.iceberg.hivelink.core;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.junit.AfterClass;
import org.junit.BeforeClass;


/**
 * hivelink refactoring:
 * This class is copied from iceberg-hive-metastore module test code
 */
public abstract class HiveMetastoreTest {

  private static final String CATALOG_NAME = "test_hive_catalog";
  protected static final String DB_NAME = "hivedb";

  protected static HiveMetaStoreClient metastoreClient;
  protected static Catalog catalog;
  protected static HiveConf hiveConf;
  protected static TestHiveMetastore metastore;

  @BeforeClass
  public static void startMetastore() throws Exception {
    HiveMetastoreTest.metastore = new TestHiveMetastore();
    metastore.start();
    HiveMetastoreTest.hiveConf = metastore.hiveConf();
    HiveMetastoreTest.metastoreClient = new HiveMetaStoreClient(hiveConf);
    String dbPath = metastore.getDatabasePath(DB_NAME);
    Database db = new Database(DB_NAME, "description", dbPath, Maps.newHashMap());
    metastoreClient.createDatabase(db);
    catalog = CatalogUtil.buildIcebergCatalog(CATALOG_NAME, ImmutableMap.of(), hiveConf);
  }

  @AfterClass
  public static void stopMetastore() {
    HiveMetastoreTest.catalog = null;

    metastoreClient.close();
    HiveMetastoreTest.metastoreClient = null;

    metastore.stop();
    HiveMetastoreTest.metastore = null;
  }
}
