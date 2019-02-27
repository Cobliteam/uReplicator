/**
 * Copyright (C) 2015-2017 Uber Technologies, Inc. (streaming-data@uber.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.mirrormaker

import java.util.concurrent.{BlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import kafka.common.KafkaException
import kafka.consumer.{ConsumerTimeoutException, ConsumerTopicStatsRegistry}
import kafka.serializer.Decoder
import kafka.utils.{IteratorTemplate, Logging}
import org.apache.kafka.common.record.{Record, TimestampType}

import scala.collection.JavaConverters.asScalaIteratorConverter


// This class is taken from https://github.com/apache/kafka/blob/1.1/core/src/main/scala/kafka/consumer/ConsumerIterator.scala
// This replaces FetchedDataChunk with FetchedRecordsDataChunk which helps us to use java Records instead of ByteBufferMessageSet
class KafkaStreamIterator[K, V](private val channel: BlockingQueue[FetchedRecordsDataChunk],
                                consumerTimeoutMs: Int,
                                private val keyDecoder: Decoder[K],
                                private val valueDecoder: Decoder[V],
                                val clientId: String)
  extends IteratorTemplate[RecordAndMetadata[K, V]] with Logging {

  private val shutdownCommand: FetchedRecordsDataChunk = new FetchedRecordsDataChunk(null, null, -1L)
  private val current: AtomicReference[Iterator[Record]] = new AtomicReference(null)
  private var currentTopicInfo: PartitionTopicInfo = null
  private var consumedOffset: Long = -1L
  private val consumerTopicStats = ConsumerTopicStatsRegistry.getConsumerTopicStat(clientId)

  override def next(): RecordAndMetadata[K, V] = {
    val record = super.next()
    if(consumedOffset < 0)
      throw new KafkaException("Offset returned by the message set is invalid %d".format(consumedOffset))
    currentTopicInfo.resetConsumeOffset(consumedOffset)
    val topic = currentTopicInfo.topic
    trace("Setting %s consumed offset to %d".format(topic, consumedOffset))
    consumerTopicStats.getConsumerTopicStats(topic).messageRate.mark()
    consumerTopicStats.getConsumerAllTopicStats().messageRate.mark()
    record
  }

  protected def makeNext(): RecordAndMetadata[K, V] = {
    var currentDataChunk: FetchedRecordsDataChunk = null
    // if we don't have an iterator, get one
    var localCurrent = current.get()
    if(localCurrent == null || !localCurrent.hasNext) {
      if (consumerTimeoutMs < 0)
        currentDataChunk = channel.take
      else {
        currentDataChunk = channel.poll(consumerTimeoutMs, TimeUnit.MILLISECONDS)
        if (currentDataChunk == null) {
          // reset state to make the iterator re-iterable
          resetState()
          throw new ConsumerTimeoutException
        }
      }
      if(currentDataChunk eq shutdownCommand) {
        debug("Received the shutdown command")
        return allDone
      } else {
        currentTopicInfo = currentDataChunk.topicInfo
        val cdcFetchOffset = currentDataChunk.fetchOffset
        val ctiConsumeOffset = currentTopicInfo.getConsumeOffset
        if (ctiConsumeOffset < cdcFetchOffset) {
          error("consumed offset: %d doesn't match fetch offset: %d for %s;\n Consumer may lose data"
            .format(ctiConsumeOffset, cdcFetchOffset, currentTopicInfo))
          currentTopicInfo.resetConsumeOffset(cdcFetchOffset)
        }
        localCurrent = currentDataChunk.records.records().iterator().asScala

        current.set(localCurrent)
      }
    }
    var record = localCurrent.next()
    // reject the messages that have already been consumed
    while (record.offset < currentTopicInfo.getConsumeOffset && localCurrent.hasNext) {
      record = localCurrent.next()
    }
    consumedOffset = record.offset() + 1

    record.ensureValid() // validate checksum of message to ensure it is valid

    // TODO: replace the timestamp create type with a valid timestamp type from records.
    new RecordAndMetadata(currentTopicInfo.topic,
      currentTopicInfo.partitionId,
      record,
      record.offset,
      keyDecoder,
      valueDecoder,
      record.timestamp(),
      TimestampType.CREATE_TIME)
  }

  def clearCurrentChunk() {
    debug("Clearing the current data chunk for this consumer iterator")
    current.set(null)
  }
}

// This class is taken from https://github.com/apache/kafka/blob/1.1/core/src/main/scala/kafka/consumer/KafkaStream.scala
// This replaces FetchedDataChunk with FetchedRecordsDataChunk which helps us to use java Records instead of ByteBufferMessageSet
class KafkaRecordStream[K,V](private val queue: BlockingQueue[FetchedRecordsDataChunk],
                        consumerTimeoutMs: Int,
                        private val keyDecoder: Decoder[K],
                        private val valueDecoder: Decoder[V],
                        val clientId: String)
  extends Iterable[RecordAndMetadata[K,V]] with java.lang.Iterable[RecordAndMetadata[K,V]] {

  val iterator: KafkaStreamIterator[K,V] =
    new KafkaStreamIterator[K,V](queue, consumerTimeoutMs, keyDecoder, valueDecoder, clientId)

  /**
    * This method clears the queue being iterated during the consumer rebalancing. This is mainly
    * to reduce the number of duplicates received by the consumer
    */
  def clear() {
    iterator.clearCurrentChunk()
  }

  override def toString: String = {
    "%s kafka record stream".format(clientId)
  }
}
