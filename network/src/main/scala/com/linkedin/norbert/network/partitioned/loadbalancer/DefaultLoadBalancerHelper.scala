/*
 * Copyright 2009-2010 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linkedin.norbert
package network
package partitioned
package loadbalancer

import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import com.linkedin.norbert.cluster.{InvalidClusterException, Node}
import com.linkedin.norbert.logging.Logging
import com.linkedin.norbert.network.client.loadbalancer.LoadBalancerHelpers
import com.linkedin.norbert.network.common.Endpoint

/**
 * A mixin trait that provides functionality to help implement a hash based <code>Router</code>.
 */
trait DefaultLoadBalancerHelper extends LoadBalancerHelpers with Logging {
  /**
   * A mapping from partition id to the <code>Node</code>s which can service that partition.
   */
  protected val partitionToNodeMap: Map[Int, (IndexedSeq[Endpoint], AtomicInteger, Array[AtomicBoolean])]

  /**
   * Given the currently available <code>Node</code>s and the total number of partitions in the cluster, this method
   * generates a <code>Map</code> of partition id to the <code>Node</code>s which service that partition.
   *
   * @param nodes the current available nodes
   * @param numPartitions the total number of partitions in the cluster
   *
   * @return a <code>Map</code> of partition id to the <code>Node</code>s which service that partition
   * @throws InvalidClusterException thrown if every partition doesn't have at least one available <code>Node</code>
   * assigned to it
   */
  protected def generatePartitionToNodeMap(nodes: Set[Endpoint], numPartitions: Int, serveRequestsIfPartitionMissing: Boolean): Map[Int, (IndexedSeq[Endpoint], AtomicInteger, Array[AtomicBoolean])] = {
    val partitionToNodeMap = (for (n <- nodes; p <- n.node.partitionIds) yield(p, n)).foldLeft(Map.empty[Int, IndexedSeq[Endpoint]]) {
      case (map, (partitionId, node)) => map + (partitionId -> (node +: map.get(partitionId).getOrElse(Vector.empty[Endpoint])))
    }

    val possiblePartitions = (0 until numPartitions).toSet
    val missingPartitions = possiblePartitions diff (partitionToNodeMap.keys.toSet)

    if(missingPartitions.size == possiblePartitions.size)
      throw new InvalidClusterException("Every single partition appears to be missing. There are %d partitions".format(numPartitions))
    else if(!missingPartitions.isEmpty) {
      if(serveRequestsIfPartitionMissing)
        log.warn("Partitions %s are unavailable, attempting to continue serving requests to other partitions.".format(missingPartitions))
      else
        throw new InvalidClusterException("Partitions %s are unavailable, cannot serve requests.".format(missingPartitions))
    }


    partitionToNodeMap.map { case (pId, endPoints) =>
      val states = new Array[AtomicBoolean](endPoints.size)
      (0 to endPoints.size -1).foreach(states(_) = new AtomicBoolean(true))
      pId -> (endPoints, new AtomicInteger(0), states) 
    }
  }

  /**
   * Calculates a <code>Node</code> which can service a request for the specified partition id.
   *
   * @param partitionId the id of the partition
   *
   * @return <code>Some</code> with the <code>Node</code> which can service the partition id, <code>None</code>
   * if there are no available <code>Node</code>s for the partition requested
   */
  protected def nodeForPartition(partitionId: Int, capability: Option[Long] = None, persistentCapability: Option[Long] = None): Option[Node] = {
    partitionToNodeMap.get(partitionId) match {
      case None =>
        return None
      case Some((endpoints, counter, states)) =>
        val es = endpoints.size
        counter.compareAndSet(java.lang.Integer.MAX_VALUE, 0)
        val idx = counter.getAndIncrement
        var i = idx
        var loopCount = 0
        do {
          val endpoint = endpoints(i % es)
          if(endpoint.canServeRequests && endpoint.node.isCapableOf(capability, persistentCapability)) {
            compensateCounter(idx, loopCount, counter);
            return Some(endpoint.node)
          }

          i = i + 1
          if (i < 0) i = 0
          loopCount = loopCount + 1
        } while (loopCount <= es)

        compensateCounter(idx, loopCount, counter);
        return Some(endpoints(idx % es).node)
    }
  }

  protected def nodesForPartition(partitionId: Int, capability: Option[Long] = None, persistentCapability: Option[Long] = None): util.LinkedHashSet[Node] = {
    partitionToNodeMap.get(partitionId) match {
      case None =>
        return new util.LinkedHashSet[Node]
      case Some((endpoints, counter, states)) =>
        val es = endpoints.size
        counter.compareAndSet(java.lang.Integer.MAX_VALUE, 0)
        val idx = counter.getAndIncrement
        var i = idx
        var loopCount = 0
        val result  = new util.LinkedHashSet[Node]
        do {
          val endpoint = endpoints(i % es)
          if(endpoint.canServeRequests && endpoint.node.isCapableOf(capability, persistentCapability)) {
            result.add(endpoint.node)
          }

          i = i + 1
          if (i < 0) i = 0
          loopCount = loopCount + 1
        } while (loopCount <= es)

        result
    }
  }

  def compensateCounter(idx: Int, count:Int, counter:AtomicInteger) {
    if (idx + 1 + count <= 0) {
      // Integer overflow
      counter.set(idx + 1 - java.lang.Integer.MAX_VALUE + count)
    }
    counter.set(idx + 1 + count)
  }
}
