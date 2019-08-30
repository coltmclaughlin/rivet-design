/**
 *  Copyright 2019 LinkedIn Corporation. All rights reserved.
 *  Licensed under the BSD 2-Clause License. See the LICENSE file in the project root for license information.
 *  See the NOTICE file in the project root for additional information regarding copyright ownership.
 */
package com.linkedin.datastream.connectors.kafka;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.StringJoiner;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linkedin.datastream.common.BrooklinEnvelope;
import com.linkedin.datastream.common.BrooklinEnvelopeMetadataConstants;
import com.linkedin.datastream.connectors.CommonConnectorMetrics;
import com.linkedin.datastream.metrics.BrooklinMetricInfo;
import com.linkedin.datastream.metrics.MetricsAware;
import com.linkedin.datastream.server.DatastreamProducerRecord;
import com.linkedin.datastream.server.DatastreamProducerRecordBuilder;
import com.linkedin.datastream.server.DatastreamTask;


/**
 * A connector task that can consume from a single kafka topic.
 */
public class KafkaConnectorTask extends AbstractKafkaBasedConnectorTask {
  private static final String CLASS_NAME = KafkaConnectorTask.class.getSimpleName();

  private static final Logger LOG = LoggerFactory.getLogger(KafkaConnectorTask.class);

  private final KafkaConnectionString _srcConnString =
      KafkaConnectionString.valueOf(_datastreamTask.getDatastreamSource().getConnectionString());
  private final KafkaConsumerFactory<?, ?> _consumerFactory;

  GroupIdConstructor _groupIdConstructor;

  /**
   * Concstructor for KafkaConnectorTask.
   * @param config Config to be used while creating KafkaConnectorTask.
   * @param task Corresponding DatastreamTask that the KafkaConnectorTask is going to be created for.
   * @param connectorName Name of the connector that the task belongs to.
   * @param groupIdConstructor Group ID constructor to use for generating consumer group name when creating
   *                           consumer inside the task.
   */
  public KafkaConnectorTask(KafkaBasedConnectorConfig config, DatastreamTask task, String connectorName,
      GroupIdConstructor groupIdConstructor) {
    super(config, task, LOG, generateMetricsPrefix(connectorName, CLASS_NAME));
    _consumerFactory = config.getConsumerFactory();
    _groupIdConstructor = groupIdConstructor;
  }

  @VisibleForTesting
  static Properties getKafkaConsumerProperties(Properties consumerProps, String groupId,
      KafkaConnectionString connectionString) {
    StringJoiner csv = new StringJoiner(",");
    connectionString.getBrokers().forEach(broker -> csv.add(broker.toString()));
    String bootstrapValue = csv.toString();

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapValue);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // auto-commits are unsafe
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
    props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, connectionString.isSecure() ? "SSL" : "PLAINTEXT");
    props.putAll(consumerProps);
    return props;
  }

  @Override
  protected void maybeCommitOffsets(Consumer<?, ?> consumer, boolean force) {
    super.maybeCommitOffsetsInternal(consumer, force);
  }

  @Override
  protected void consumerSubscribe() {
    KafkaConnectionString srcConnString =
        KafkaConnectionString.valueOf(_datastreamTask.getDatastreamSource().getConnectionString());
    _consumer.subscribe(Collections.singletonList(srcConnString.getTopicName()), this);
  }

  /**
   * Create kafka consumer
   * @param consumerFactory Instance of KafkaConsumerFactory that is used to create consumer.
   * @param consumerProps Properties of the consumer to be created.
   * @param groupId Consumer group that the consumer to create is part of.
   * @param connectionString Kafka cluster the consumer should connect to.
   * @return Instance of Consumer.
   */
  public static Consumer<?, ?> createConsumer(KafkaConsumerFactory<?, ?> consumerFactory, Properties consumerProps,
      String groupId, KafkaConnectionString connectionString) {

    Properties props = getKafkaConsumerProperties(consumerProps, groupId, connectionString);
    return consumerFactory.createConsumer(props);
  }

  @Override
  protected Consumer<?, ?> createKafkaConsumer(Properties consumerProps) {

    return createConsumer(_consumerFactory, consumerProps, getKafkaGroupId(_datastreamTask, _groupIdConstructor, _consumerMetrics, LOG),
        _srcConnString);
  }

  /**
   * Get Brooklin metrics info
   * @param connectorName Connector name to use
   */
  public static List<BrooklinMetricInfo> getMetricInfos(String connectorName) {
    return AbstractKafkaBasedConnectorTask.getMetricInfos(
        generateMetricsPrefix(connectorName, CLASS_NAME) + MetricsAware.KEY_REGEX);
  }

  @Override
  protected DatastreamProducerRecord translate(ConsumerRecord<?, ?> fromKafka, Instant readTime) {
    HashMap<String, String> metadata = new HashMap<>();
    metadata.put("kafka-origin", _srcConnString.toString());
    int partition = fromKafka.partition();
    String partitionStr = String.valueOf(partition);
    metadata.put("kafka-origin-partition", partitionStr);
    String offsetStr = String.valueOf(fromKafka.offset());
    metadata.put("kafka-origin-offset", offsetStr);

    long eventsSourceTimestamp = readTime.toEpochMilli();
    if (fromKafka.timestampType() == TimestampType.CREATE_TIME) {
      // If the Kafka header contains the create time. We store the event creation time as event timestamp
      metadata.put(BrooklinEnvelopeMetadataConstants.EVENT_TIMESTAMP, String.valueOf(fromKafka.timestamp()));
    } else if (fromKafka.timestampType() == TimestampType.LOG_APPEND_TIME) {
      // If the Kafka header contains the log append time, We use that as event source Timestamp
      // which will be used to calculate the SLA.
      metadata.put(BrooklinEnvelopeMetadataConstants.SOURCE_TIMESTAMP, String.valueOf(fromKafka.timestamp()));
      metadata.put(BrooklinEnvelopeMetadataConstants.EVENT_TIMESTAMP, String.valueOf(readTime.toEpochMilli()));
      eventsSourceTimestamp = fromKafka.timestamp();
    }

    BrooklinEnvelope envelope = new BrooklinEnvelope(fromKafka.key(), fromKafka.value(), null, metadata);
    DatastreamProducerRecordBuilder builder = new DatastreamProducerRecordBuilder();
    builder.addEvent(envelope);
    builder.setEventsSourceTimestamp(eventsSourceTimestamp);
    builder.setPartition(partition); // assume source partition count is same as dest
    builder.setSourceCheckpoint(partitionStr + "-" + offsetStr);

    return builder.build();
  }

  /**
   * Get Kafka group ID of given task
   * @param task Task for which group ID is generated.
   * @param groupIdConstructor GroupIdConstructor to use for generating group ID.
   * @param consumerMetrics CommonConnectorMetrics to use for reporting errors.
   * @param logger Logger for logging information.
   */
  @VisibleForTesting
  public static String getKafkaGroupId(DatastreamTask task, GroupIdConstructor groupIdConstructor,
      CommonConnectorMetrics consumerMetrics, Logger logger) {
    try {
      return groupIdConstructor.getTaskGroupId(task, Optional.of(logger));
    } catch (Exception e) {
      consumerMetrics.updateErrorRate(1, "Can't find group ID", e);
      throw e;
    }
  }
}