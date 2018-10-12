package org.apache.spark.sql.redis

import java.util.{UUID, List => JList, Map => JMap}

import com.redislabs.provider.redis.rdd.Keys
import com.redislabs.provider.redis.util.Logging
import com.redislabs.provider.redis.{RedisConfig, RedisEndpoint, RedisNode, toRedisContext}
import org.apache.commons.lang3.SerializationUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.GenericRow
import org.apache.spark.sql.redis.RedisSourceRelation._
import org.apache.spark.sql.sources.{BaseRelation, Filter, InsertableRelation, PrunedFilteredScan}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import redis.clients.jedis.Protocol

import scala.collection.JavaConversions
import scala.collection.JavaConversions._

class RedisSourceRelation(override val sqlContext: SQLContext,
                          parameters: Map[String, String],
                          userSpecifiedSchema: Option[StructType])
  extends BaseRelation
    with InsertableRelation
    with PrunedFilteredScan
    with Keys
    with Serializable
    with Logging {

  private implicit val redisConfig: RedisConfig = {
    new RedisConfig(
      if ((parameters.keySet & Set("host", "port", "auth", "dbNum", "timeout")).isEmpty) {
        new RedisEndpoint(sqlContext.sparkContext.getConf)
      } else {
        val host = parameters.getOrElse("host", Protocol.DEFAULT_HOST)
        val port = parameters.get("port").map(_.toInt).getOrElse(Protocol.DEFAULT_PORT)
        val auth = parameters.getOrElse("auth", null)
        val dbNum = parameters.get("dbNum").map(_.toInt).getOrElse(Protocol.DEFAULT_DATABASE)
        val timeout = parameters.get("timeout").map(_.toInt).getOrElse(Protocol.DEFAULT_TIMEOUT)
        RedisEndpoint(host, port, auth, dbNum, timeout)
      }
    )
  }

  logInfo(s"Redis config initial host: ${redisConfig.initialHost}")

  @transient private val sc = sqlContext.sparkContext
  @volatile private var currentSchema: StructType = _

  /** parameters **/
  private val tableNameOpt: Option[String] = parameters.get(SqlOptionTableName)
  private val keysPatternOpt: Option[String] = parameters.get(SqlOptionKeysPattern)
  private val keyColumn = parameters.get(SqlOptionKeyColumn)
  private val numPartitions = parameters.get(SqlOptionNumPartitions).map(_.toInt)
    .getOrElse(SqlOptionNumPartitionsDefault)
  private val inferSchemaEnabled = parameters.get(SqlOptionInferSchema).exists(_.toBoolean)
  private val persistenceModel = parameters.getOrDefault(SqlOptionModel, SqlOptionModelHash)
  private val persistence = RedisPersistence(persistenceModel)
  private val ttl = parameters.get(SqlOptionTTL).map(_.toInt).getOrElse(0)

  // check specified parameters
  if (tableNameOpt.isDefined && keysPatternOpt.isDefined) {
    throw new IllegalArgumentException(s"Both options '$SqlOptionTableName' and '$SqlOptionTableName' are set. " +
      s"You should only use either one.")
  }

  private def tableName(): String = {
    tableNameOpt.getOrElse(throw new IllegalArgumentException(s"Option '$SqlOptionTableName' is not set."))
  }

  override def schema: StructType = {
    if (currentSchema == null) {
      currentSchema = userSpecifiedSchema
        .getOrElse {
          if (inferSchemaEnabled) {
            inferSchema()
          } else {
            loadSchema()
          }
        }
    }
    currentSchema
  }

  /**
    * redis key pattern for rows, based either on the 'keys.pattern' or 'table' parameter
    */
  private def dataKeyPattern(): String = {
    keysPatternOpt
      .orElse(
        tableNameOpt.map(tableName => tableDataKeyPattern(tableName))
      )
      .getOrElse(throw new IllegalArgumentException(s"Neither '$SqlOptionKeysPattern' or '$SqlOptionTableName' option is set."))
  }


  private def inferSchema(): StructType = {
    val keys = sc.fromRedisKeyPattern(dataKeyPattern())
    if (keys.isEmpty()) {
      throw new IllegalStateException("No key is available")
    } else {
      val firstKey = keys.first()
      val node = getMasterNode(redisConfig.hosts, firstKey)
      scanRows(node, Seq(firstKey), Seq())
        .collectFirst {
          case r: Row => r.schema
        }
        .getOrElse {
          throw new IllegalStateException("No row is available")
        }
    }
  }

  private def dataKeyId(row: Row): String = {
    val id = keyColumn.map(id => row.getAs[Any](id)).map(_.toString).getOrElse(uuid())
    dataKey(tableName(), id)
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    val schema = userSpecifiedSchema.getOrElse(data.schema)
    // write schema, so that we can load dataframe back
    currentSchema = saveSchema(schema)
    if (overwrite) {
      // truncate the table
      redisConfig.hosts.foreach { node =>
        val conn = node.connect()
        val keys = conn.keys(dataKeyPattern())
        if (keys.nonEmpty) {
          val keySeq = JavaConversions.asScalaSet(keys).toSeq
          conn.del(keySeq: _*)
        }
        conn.close()
      }
    }

    // write data
    data.foreachPartition { partition =>
      val rowsWithKey: Map[String, Row] = partition.map(row => dataKeyId(row) -> row).toMap
      groupKeysByNode(redisConfig.hosts, rowsWithKey.keysIterator).foreach { case (node, keys) =>
        val conn = node.connect()
        val pipeline = conn.pipelined()
        keys.foreach { key =>
          val row = rowsWithKey(key)
          val encodedRow = persistence.encodeRow(row)
          persistence.save(pipeline, key, encodedRow, ttl)
        }
        pipeline.sync()
        conn.close()
      }
    }
  }

  def isEmpty: Boolean =
    sc.fromRedisKeyPattern(dataKeyPattern()).isEmpty()

  def nonEmpty: Boolean = !isEmpty

  def saveSchema(schema: StructType): StructType = {
    val key = schemaKey(tableName())
    logInfo(s"saving schema $key")
    val schemaNode = getMasterNode(redisConfig.hosts, key)
    val conn = schemaNode.connect()
    val schemaBytes = SerializationUtils.serialize(schema)
    conn.set(key.getBytes, schemaBytes)
    conn.close()
    schema
  }

  def loadSchema(): StructType = {
    val key = schemaKey(tableName())
    logInfo(s"loading schema $key")
    val schemaNode = getMasterNode(redisConfig.hosts, key)
    val conn = schemaNode.connect()
    val schemaBytes = conn.get(key.getBytes)
    if (schemaBytes == null) {
      throw new IllegalStateException(s"Unable to read dataframe schema by key '$key'. " +
        s"If dataframe was not persisted by Spark, provide a schema explicitly with .schema() or use 'infer.schema' option. ")
    }
    val schema = SerializationUtils.deserialize[StructType](schemaBytes)
    conn.close()
    schema
  }

  override def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    logInfo("build scan")
    val keysRdd = sc.fromRedisKeyPattern(dataKeyPattern(), partitionNum = numPartitions)
    if (requiredColumns.isEmpty) {
      keysRdd.map { _ =>
        new GenericRow(Array[Any]())
      }
    } else {
      keysRdd.mapPartitions { partition =>
        groupKeysByNode(redisConfig.hosts, partition)
          .flatMap { case (node, keys) =>
            scanRows(node, keys, requiredColumns)
          }
          .iterator
      }
    }
  }

  private def scanRows(node: RedisNode, keys: Seq[String], requiredColumns: Seq[String]) = {
    def filteredSchema(): StructType = {
      val requiredColumnsSet = Set(requiredColumns: _*)
      val filteredFields = schema.fields
        .filter { f =>
          requiredColumnsSet.contains(f.name)
        }
      StructType(filteredFields)
    }

    val conn = node.connect()
    val pipeline = conn.pipelined()
    keys
      .foreach { key =>
        logTrace(s"key $key")
        persistence.load(pipeline, key, requiredColumns)
      }
    val pipelineValues = pipeline.syncAndReturnAll()
    val rows =
      if (requiredColumns.isEmpty || persistenceModel == SqlOptionModelBinary) {
        pipelineValues
          .map {
            case jmap: JMap[_, _] => jmap.toMap
            case value: Any => value
          }
          .map { value =>
            persistence.decodeRow(value, schema, inferSchemaEnabled)
          }
      } else {
        pipelineValues.map { case values: JList[String] =>
          val value = requiredColumns.zip(values).toMap
          persistence.decodeRow(value, filteredSchema(), inferSchemaEnabled)
        }
      }
    conn.close()
    rows
  }

  override def unhandledFilters(filters: Array[Filter]): Array[Filter] = filters
}

object RedisSourceRelation {

  def schemaKey(tableName: String): String = s"_spark:$tableName:schema"

  def dataKey(tableName: String, id: String = uuid()): String = {
    s"$tableName:$id"
  }

  def uuid(): String = UUID.randomUUID().toString.replace("-", "")

  def tableDataKeyPattern(tableName: String): String = s"$tableName:*"
}