/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.thriftserver

import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.hive.service.cli.HiveSQLException

import org.apache.spark.TaskKilled
import org.apache.spark.scheduler.{SparkListener, SparkListenerTaskEnd}
import org.apache.spark.sql.internal.SQLConf

trait ThriftServerWithSparkContextSuite extends SharedThriftServer {

  test("the scratch dir will be deleted during server start but recreated with new operation") {
    assert(tempScratchDir.exists())
  }

  test("SPARK-29911: Uncache cached tables when session closed") {
    val cacheManager = spark.sharedState.cacheManager
    val globalTempDB = spark.sharedState.globalTempViewManager.database
    withJdbcStatement { statement =>
      statement.execute("CACHE TABLE tempTbl AS SELECT 1")
    }
    // the cached data of local temporary view should be uncached
    assert(cacheManager.isEmpty)
    try {
      withJdbcStatement { statement =>
        statement.execute("CREATE GLOBAL TEMP VIEW globalTempTbl AS SELECT 1, 2")
        statement.execute(s"CACHE TABLE $globalTempDB.globalTempTbl")
      }
      // the cached data of global temporary view shouldn't be uncached
      assert(!cacheManager.isEmpty)
    } finally {
      withJdbcStatement { statement =>
        statement.execute(s"UNCACHE TABLE IF EXISTS $globalTempDB.globalTempTbl")
      }
      assert(cacheManager.isEmpty)
    }
  }

  test("Full stack traces as error message for jdbc or thrift client") {
    val sql = "select date_sub(date'2011-11-11', '1.2')"
    withCLIServiceClient { client =>
      val sessionHandle = client.openSession(user, "")

      val confOverlay = new java.util.HashMap[java.lang.String, java.lang.String]
      val e = intercept[HiveSQLException] {
        client.executeStatement(
          sessionHandle,
          sql,
          confOverlay)
      }

      assert(e.getMessage
        .contains("The second argument of 'date_sub' function needs to be an integer."))
      assert(!e.getMessage.contains("" +
        "java.lang.NumberFormatException: invalid input syntax for type numeric: 1.2"))
    }

    withJdbcStatement { statement =>
      val e = intercept[SQLException] {
        statement.executeQuery(sql)
      }
      assert(e.getMessage
        .contains("The second argument of 'date_sub' function needs to be an integer."))
      assert(e.getMessage.contains("" +
        "java.lang.NumberFormatException: invalid input syntax for type numeric: 1.2"))
    }
  }

  test("SPARK-33526: Add config to control if cancel invoke interrupt task on thriftserver") {
    withJdbcStatement { statement =>
      val forceCancel = new AtomicBoolean(false)
      val listener = new SparkListener {
        override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = {
          assert(taskEnd.reason.isInstanceOf[TaskKilled])
          if (forceCancel.get()) {
            assert(System.currentTimeMillis() - taskEnd.taskInfo.launchTime < 1000)
          } else {
            // avoid accuracy, we check 2s instead of 3s.
            assert(System.currentTimeMillis() - taskEnd.taskInfo.launchTime >= 2000)
          }
        }
      }

      spark.sparkContext.addSparkListener(listener)
      try {
        Seq(true, false).foreach { force =>
          statement.setQueryTimeout(0)
          statement.execute(s"SET ${SQLConf.THRIFTSERVER_FORCE_CANCEL.key}=$force")
          statement.setQueryTimeout(1)
          forceCancel.set(force)
          val e = intercept[SQLException] {
            statement.execute("select java_method('java.lang.Thread', 'sleep', 3000L)")
          }.getMessage
          assert(e.contains("Query timed out"))
        }
      } finally {
        spark.sparkContext.removeSparkListener(listener)
      }
    }
  }
}


class ThriftServerWithSparkContextInBinarySuite extends ThriftServerWithSparkContextSuite {
  override def mode: ServerMode.Value = ServerMode.binary
}

class ThriftServerWithSparkContextInHttpSuite extends ThriftServerWithSparkContextSuite {
  override def mode: ServerMode.Value = ServerMode.http
}
