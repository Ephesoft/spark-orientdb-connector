
/** Copyright 2015, Metreta Information Technology s.r.l. */

package com.metreta.spark.orientdb.connector.rdd

import scala.collection.JavaConversions._
import scala.reflect.ClassTag
import org.apache.spark._
import org.apache.spark.Dependency
import org.apache.spark.Partitioner._
import org.apache.spark.SparkContext
import com.metreta.spark.orientdb.connector.api.OrientDBConnector
import com.metreta.spark.orientdb.connector.rdd.partitioner.ClassRDDPartitioner
import com.metreta.spark.orientdb.connector.rdd.partitioner.OrientPartition
import com.orientechnologies.orient.client.remote.OServerAdmin
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.db.record.ridbag.ORidBag
import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.id.ORecordId
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.query.OResultSet
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import com.orientechnologies.orient.server.OServer
import com.orientechnologies.orient.server.distributed.ODistributedConfiguration
import com.orientechnologies.orient.server.hazelcast.OHazelcastPlugin
import com.orientechnologies.orient.server.OServerMain
import com.orientechnologies.orient.core.sql.query.OSQLAsynchQuery
import com.metreta.spark.orientdb.connector.rdd.partitioner.PartitionName

case class OrientDocumentException(message: String) extends Exception(message)
case class OrientDocument(val oClassName: String,
                          val oRid: String,
                          val oColumnNames: IndexedSeq[String],
                          val oColumnValues: IndexedSeq[Any]) extends OrientEntry(oClassName, oRid, oColumnNames, oColumnValues)

/**
 * @author Simone Bronzin
 *
 */
class OrientClassRDD[T] private[connector] (@transient val sc: SparkContext,
                                            val connector: OrientDBConnector,
                                            val from: String,
                                            val columns: String = "",
                                            val where: String = "",
                                            val limit: String = "",
                                            val opts: String = "",
                                            val depth: Option[Int] = None,
                                            val query: String = "")(implicit val classTag: ClassTag[T]//,
                                                                    //val connector: OrientDBConnector = OrientDBConnector(sc.getConf)
                                                                    )
    extends OrientRDD[OrientDocument](sc, Seq.empty) {

  /**
   * Fetches the data from the given partition.
   * @param split
   * @param context
   * @return a ClassRDD[OrientDocument]
   */
  override def compute(split: Partition, context: TaskContext): Iterator[OrientDocument] = {

    //(List(OrientDocument("1", "1", null, null), OrientDocument("2", "2", null, null), OrientDocument("3", "3", null, null))).toIterator

    val session = connector.databaseDocumentTx()

    val partition = split.asInstanceOf[OrientPartition]

    val cluster = partition.partitionName.clusterName
    
    val queryString = createQueryString(cluster, columns, where, limit, depth, opts) match {
      case Some(i) => i
      case None =>
        throw OrientDocumentException("wrong number of parameters")
        "error"
    }
    val query = new OSQLSynchQuery(queryString)
    
    val res: OResultSet[Any] = connector.query(session, query)
    logInfo(s"Fetching data from: $cluster")

    val res2 = res.map { v =>
      var x: ODocument = null
      if (v.isInstanceOf[ORecordId]) {
        x = v.asInstanceOf[ORecordId].getRecord.asInstanceOf[ODocument]
      } else
        x = v.asInstanceOf[ODocument]

      OrientDocument(
        x.getClassName,
        x.getIdentity.toString(),
        x.fieldNames().toIndexedSeq,
        serialize(x.fieldValues()).toIndexedSeq)
    }
    res2.iterator
  }

  private def serialize(fieldValues: Any): Array[Any] =
    fieldValues.asInstanceOf[Array[Any]] map {
      case z: ORidBag => z.toString()
      case z          => z
    }

  /**
   * Builds a query string.
   * @param cluster
   * @param where
   * @param depth
   * @return OrientDB query string.
   */
  def createQueryString(cluster: String, columns: String, where: String, limit: String, depth: Option[Int], opts: String): Option[String] = {
    
    val myLimit = if(limit == "") "-1" else limit
    val myColumns = if(columns == "") "*" else columns
        
    if (where == "" && depth.isEmpty) {
      Option("select " + myColumns + " from cluster:" + cluster + " " + opts + " limit " + myLimit )
    } else if (where != "" && depth.isEmpty) {
      Option("select " + myColumns + " from cluster:" + cluster + " where " + where + " " + opts + " limit " + myLimit)
    } else if (where == "" && depth.isDefined) {
      Option("traverse " + myColumns + " from cluster:" + cluster + " while $depth < " + depth.get)
    } else {
      None
    }
  }
  /**
   * @return Spark partitions from a given OrientdDB class.
   */
  override def getPartitions: Array[Partition] = {

    val partitioner = new ClassRDDPartitioner(connector, from)
    val partitions = partitioner.getPartitions()

    logInfo(s"Found ${partitions.length} clusters.")

    partitions
  }

  object OrientClassRDD {

  }
}