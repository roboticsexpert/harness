/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * ActionML licenses this file to You under the Apache License, Version 2.0
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

package com.actionml.engines.ur

import com.actionml.core.search.elasticsearch.ElasticSearchClient
import com.typesafe.scalalogging.LazyLogging
import org.apache.mahout.math.indexeddataset.IndexedDataset
import org.apache.mahout.sparkbindings.indexeddataset.IndexedDatasetSpark
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.json4s.JsonAST._
import org.joda.time.DateTime

/** Universal Recommender models to save in ES */
class URModel(
    coocurrenceMatrices: Seq[(String, IndexedDataset)] = Seq.empty,
    propertiesRDDs: Seq[RDD[(String, Map[String, Any])]] = Seq.empty,
    typeMappings: Map[String, (String, Boolean)] = Map.empty) // maps fieldname that need type mapping in Elasticsearch
    (implicit sc: SparkContext, es: ElasticSearchClient)
  extends LazyLogging {


  /** Save all rules to be indexed by Elasticsearch and queried for recs
    *  This will is something like a table with row IDs = item IDs and separate rules for all
    *  cooccurrence and cross-cooccurrence correlators and metadata for each item. Metadata rules are
    *  limited to text term collections so vector types. Scalar values can be used but depend on
    *  Elasticsearch's support. One exception is the Data scalar, which is also supported
    *  @return always returns true since most other reasons to not save cause exceptions
    */
  def save(
    dateNames: Set[String],
    esIndex: String,
    esType: String,
    numESWriteConnections: Option[Int] = None): Boolean = {

    logger.trace(s"Start saving the model to Elasticsearch")

    // for ES we need to create the entire index in an rdd of maps, one per item so we'll
    // convert cooccurrence matrices into correlators as RDD[(itemID, (actionName, Seq[itemID])]
    // do they need to be in Elasticsearch format
    logger.trace("Converting cooccurrence matrices into correlators")
    val correlatorRDDs: Seq[RDD[(String, Map[String, Any])]] = coocurrenceMatrices.map {
      case (actionName, dataset) =>
        dataset.asInstanceOf[IndexedDatasetSpark].toStringMapRDD(actionName)
    }

    logger.trace("Group all properties RDD")
    //val collectedCorrelators = correlatorRDDs.map(_.collect())

    val groupedRDD: RDD[(String, Map[String, Any])] = groupAll(correlatorRDDs ++ propertiesRDDs)
    //    logger.debug(s"Grouped RDD\n${groupedRDD.take(25).mkString("\n")}")

    val esRDD: RDD[Map[String, Any]] = groupedRDD.mapPartitions { iter =>
      iter map {
        case (itemId, itemProps) =>
          val propsMap = itemProps.map {
            case (propName, propValue) =>
              propName -> URModel.extractJvalue(dateNames, propName, propValue)
          }
          propsMap + ("id" -> itemId)
      }
    }

    // todo: this should be replaced with (a) an optional list of properties in the params json because now it
    // goes through every element!!! ALL DATA! to find it's property name, or (b) maybe this is better as a DB query???
    val esFields: List[String] = esRDD.flatMap(_.keySet).distinct().collect.toList
    // logger.info(s"ES rules[${esFields.size}]: $esFields")
    es.hotSwap(esRDD, esFields, typeMappings, numESWriteConnections)
    true
  }

  // Something in the second def of this function hangs on some data, reverting so this
  def groupAll(fields: Seq[RDD[(String, Map[String, Any])]]): RDD[(String, Map[String, Any])] = {
   //val retval = rules.head.fullOuterJoin()[Map[String, Any]](groupAll(rules.drop(1)))
    //def groupAll( rules: Seq[RDD[(String, (Map[String, Any]))]]): RDD[(String, (Map[String, Any]))] = {
    //if (rules.size > 1 && !rules.head.isEmpty() && !rules(1).isEmpty()) {
    val retval = if (fields.size > 1) {
      fields.head.cogroup[Map[String, Any]](groupAll(fields.drop(1))).map {
        case (key, pairMapSeqs) =>
          // to be safe merge all maps but should only be one per rdd element
          val rdd1Maps = pairMapSeqs._1.foldLeft(Map.empty[String, Any])(_ ++ _)
          val rdd2Maps = pairMapSeqs._2.foldLeft(Map.empty[String, Any])(_ ++ _)
          val fullMap = rdd1Maps ++ rdd2Maps
          (key, fullMap)
      }
    } else {
      fields.head
    }
    //val m = retval.collect()
    //logger.info(s"$m")
    retval
  }

}

object URModel {

  def extractJvalue(dateNames: Set[String], key: String, value: Any): Any = value match {
    case JArray(list) => list.map(extractJvalue(dateNames, key, _))
    case JString(s) =>
      if (dateNames.contains(key)) {
        new DateTime(s).toDate
      } else if (RankingFieldName.toSeq.contains(key)) {
        s.toDouble
      } else {
        s
      }
    case JDouble(double) => double
    case JInt(int)       => int
    case JBool(bool)     => bool
    case _               => value
  }

}
