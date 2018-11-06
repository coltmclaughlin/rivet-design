package com.linkedin.datastream.connectors.kafka.mirrormaker;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linkedin.datastream.common.BrooklinEnvelope;
import com.linkedin.datastream.common.BrooklinEnvelopeMetadataConstants;
import com.linkedin.datastream.common.DatastreamConstants;
import com.linkedin.datastream.common.ReflectionUtils;
import com.linkedin.datastream.common.VerifiableProperties;
import com.linkedin.datastream.connectors.CommonConnectorMetrics;
import com.linkedin.datastream.connectors.kafka.AbstractKafkaBasedConnectorTask;
import com.linkedin.datastream.connectors.kafka.GroupIdConstructor;
import com.linkedin.datastream.connectors.kafka.KafkaBasedConnectorConfig;
import com.linkedin.datastream.connectors.kafka.KafkaBrokerAddress;
import com.linkedin.datastream.connectors.kafka.KafkaConnectionString;
import com.linkedin.datastream.connectors.kafka.KafkaConsumerFactory;
import com.linkedin.datastream.connectors.kafka.KafkaDatastreamStatesResponse;
import com.linkedin.datastream.connectors.kafka.PausedSourcePartitionMetadata;
import com.linkedin.datastream.metrics.BrooklinMetricInfo;
import com.linkedin.datastream.metrics.MetricsAware;
import com.linkedin.datastream.server.DatastreamProducerRecord;
import com.linkedin.datastream.server.DatastreamProducerRecordBuilder;
import com.linkedin.datastream.server.DatastreamTask;
import com.linkedin.datastream.server.FlushlessEventProducerHandler;


/**
 * KafkaMirrorMakerConnectorTask consumes from Kafka using regular expression pattern subscription. This means that the
 * task is consuming from multiple topics at once. When a new topic falls into the subscription, the task should
 * create the topic in the destination before attempting to produce to it.
 *
 * This task is responsible for specifying the destination for every DatastreamProducerRecord it sends downstream. As
 * such, the Datastream destination connection string should be a format String, where "%s" should be replaced by the
 * specific topic to send to.
 *
 * If flushless mode is enabled, the task will not invoke flush on the producer unless shutdown is requested. In
 * flushless mode, task keeps track of the safe checkpoints for each source partition and only commits offsets that were
 * acknowledged in the producer callback. Flow control can only be used in flushless mode.
 */
public class KafkaMirrorMakerConnectorTask extends AbstractKafkaBasedConnectorTask {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaMirrorMakerConnectorTask.class.getName());
  private static final String CLASS_NAME = KafkaMirrorMakerConnectorTask.class.getSimpleName();

  private static final String KAFKA_ORIGIN_CLUSTER = "kafka-origin-cluster";
  private static final String KAFKA_ORIGIN_TOPIC = "kafka-origin-topic";
  private static final String KAFKA_ORIGIN_PARTITION = "kafka-origin-partition";
  private static final String KAFKA_ORIGIN_OFFSET = "kafka-origin-offset";

  // constants for flushless mode and flow control
  protected static final String CONFIG_MAX_IN_FLIGHT_MSGS_THRESHOLD = "maxInFlightMessagesThreshold";
  protected static final String CONFIG_MIN_IN_FLIGHT_MSGS_THRESHOLD = "minInFlightMessagesThreshold";
  protected static final String CONFIG_FLOW_CONTROL_ENABLED = "flowControlEnabled";
  protected static final long DEFAULT_MAX_IN_FLIGHT_MSGS_THRESHOLD = 5000;
  protected static final long DEFAULT_MIN_IN_FLIGHT_MSGS_THRESHOLD = 1000;

  // constants for topic manager
  public static final String TOPIC_MANAGER_FACTORY = "topicManagerFactory";
  public static final String DEFAULT_TOPIC_MANAGER_FACTORY =
      "com.linkedin.datastream.connectors.kafka.mirrormaker.NoOpTopicManagerFactory";
  public static final String DOMAIN_TOPIC_MANAGER = "topicManager";

  private final KafkaConsumerFactory<?, ?> _consumerFactory;
  private final KafkaConnectionString _mirrorMakerSource;

  // Topic manager can be used to handle topic related tasks that mirror maker connector needs to do.
  // Topic manager is invoked every time there is a new partition assignment (for both partitions assigned and revoked),
  // and also before every poll call.
  private final TopicManager _topicManager;

  // variables for flushless mode and flow control
  private final boolean _isFlushlessModeEnabled;
  private final boolean _isIdentityMirroringEnabled;
  private FlushlessEventProducerHandler<Long> _flushlessProducer = null;
  private boolean _flowControlEnabled = false;
  private long _maxInFlightMessagesThreshold;
  private long _minInFlightMessagesThreshold;
  private int _flowControlTriggerCount = 0;

  private GroupIdConstructor _groupIdConstructor;

  public KafkaMirrorMakerConnectorTask(KafkaBasedConnectorConfig config, DatastreamTask task, String connectorName,
      boolean isFlushlessModeEnabled, GroupIdConstructor groupIdConstructor) {
    super(config, task, LOG, generateMetricsPrefix(connectorName, CLASS_NAME));
    _consumerFactory = config.getConsumerFactory();
    _mirrorMakerSource = KafkaConnectionString.valueOf(_datastreamTask.getDatastreamSource().getConnectionString());

    _isFlushlessModeEnabled = isFlushlessModeEnabled;
    _isIdentityMirroringEnabled = KafkaMirrorMakerDatastreamMetadata.isIdentityPartitioningEnabled(_datastream);
    _groupIdConstructor = groupIdConstructor;

    if (_isFlushlessModeEnabled) {
      _flushlessProducer = new FlushlessEventProducerHandler<>(_producer);
      _flowControlEnabled = config.getConnectorProps().getBoolean(CONFIG_FLOW_CONTROL_ENABLED, false);
      _maxInFlightMessagesThreshold =
          config.getConnectorProps().getLong(CONFIG_MAX_IN_FLIGHT_MSGS_THRESHOLD, DEFAULT_MAX_IN_FLIGHT_MSGS_THRESHOLD);
      _minInFlightMessagesThreshold =
          config.getConnectorProps().getLong(CONFIG_MIN_IN_FLIGHT_MSGS_THRESHOLD, DEFAULT_MIN_IN_FLIGHT_MSGS_THRESHOLD);
      LOG.info("Flushless mode is enabled for task: {}, with flowControlEnabled={}, minInFlightMessagesThreshold={}, "
              + "maxInFlightMessagesThreshold={}", task, _flowControlEnabled, _minInFlightMessagesThreshold,
          _maxInFlightMessagesThreshold);
    }

    // create topic manager
    // by default it creates a NoOpTopicManager.
    VerifiableProperties connectorProperties = config.getConnectorProps();
    Properties topicManagerProperties = new Properties();
    String topicManagerFactoryName = DEFAULT_TOPIC_MANAGER_FACTORY;
    if (null != connectorProperties &&
        null != connectorProperties.getProperty(TOPIC_MANAGER_FACTORY)) {
      topicManagerProperties = connectorProperties.getDomainProperties(DOMAIN_TOPIC_MANAGER);
      topicManagerFactoryName = connectorProperties.getProperty(TOPIC_MANAGER_FACTORY);
    }

    TopicManagerFactory topicManagerFactory = ReflectionUtils.createInstance(topicManagerFactoryName);
    _topicManager =
        topicManagerFactory.createTopicManager(_datastreamTask, _datastream, _groupIdConstructor, _consumerFactory,
            topicManagerProperties, _consumerMetrics);
  }

  @Override
  protected Consumer<?, ?> createKafkaConsumer(Properties consumerProps) {
    Properties properties = new Properties();
    properties.putAll(consumerProps);
    String bootstrapValue = String.join(KafkaConnectionString.BROKER_LIST_DELIMITER,
        _mirrorMakerSource.getBrokers().stream().map(KafkaBrokerAddress::toString).collect(Collectors.toList()));
    properties.putIfAbsent(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapValue);
    properties.putIfAbsent(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
        Boolean.FALSE.toString()); // auto-commits are unsafe
    properties.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, CONSUMER_AUTO_OFFSET_RESET_CONFIG_EARLIEST);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG,
        getMirrorMakerGroupId(_datastreamTask, _groupIdConstructor, _consumerMetrics, LOG));
    LOG.info("Creating Kafka consumer for task {} with properties {}", _datastreamTask, properties);
    return _consumerFactory.createConsumer(properties);
  }

  @Override
  protected void consumerSubscribe() {
    LOG.info("About to subscribe to source: {}", _mirrorMakerSource.getTopicName());
    _consumer.subscribe(Pattern.compile(_mirrorMakerSource.getTopicName()), this);
  }

  @Override
  protected DatastreamProducerRecord translate(ConsumerRecord<?, ?> fromKafka, Instant readTime) throws Exception {
    HashMap<String, String> metadata = new HashMap<>();
    metadata.put(KAFKA_ORIGIN_CLUSTER, _mirrorMakerSource.toString());
    String topic = fromKafka.topic();
    metadata.put(KAFKA_ORIGIN_TOPIC, topic);
    int partition = fromKafka.partition();
    String partitionStr = String.valueOf(partition);
    metadata.put(KAFKA_ORIGIN_PARTITION, partitionStr);
    long offset = fromKafka.offset();
    String offsetStr = String.valueOf(offset);
    metadata.put(KAFKA_ORIGIN_OFFSET, offsetStr);
    metadata.put(BrooklinEnvelopeMetadataConstants.EVENT_TIMESTAMP, String.valueOf(readTime.toEpochMilli()));
    BrooklinEnvelope envelope = new BrooklinEnvelope(fromKafka.key(), fromKafka.value(), null, metadata);
    DatastreamProducerRecordBuilder builder = new DatastreamProducerRecordBuilder();
    builder.addEvent(envelope);
    builder.setEventsSourceTimestamp(readTime.toEpochMilli());
    builder.setSourceCheckpoint(new KafkaMirrorMakerCheckpoint(topic, partition, offset).toString());
    builder.setDestination(_datastreamTask.getDatastreamDestination()
        .getConnectionString()
        .replace(KafkaMirrorMakerConnector.MM_TOPIC_PLACEHOLDER, topic));
    if (_isIdentityMirroringEnabled) {
      builder.setPartition(partition);
    }
    return builder.build();
  }

  @Override
  protected void sendDatastreamProducerRecord(DatastreamProducerRecord datastreamProducerRecord) throws Exception {
    if (_isFlushlessModeEnabled) {
      KafkaMirrorMakerCheckpoint sourceCheckpoint =
          new KafkaMirrorMakerCheckpoint(datastreamProducerRecord.getCheckpoint());
      String topic = sourceCheckpoint.getTopic();
      int partition = sourceCheckpoint.getPartition();
      _flushlessProducer.send(datastreamProducerRecord, topic, partition, sourceCheckpoint.getOffset());
      if (_flowControlEnabled) {
        TopicPartition tp = new TopicPartition(topic, partition);
        long inFlightMessageCount = _flushlessProducer.getInFlightCount(topic, partition);
        if (inFlightMessageCount > _maxInFlightMessagesThreshold) {
          // add the partition to the pause list
          LOG.warn(
              "In-flight message count of {} for topic partition {} exceeded maxInFlightMessagesThreshold of {}. Will pause partition.",
              inFlightMessageCount, tp, _maxInFlightMessagesThreshold);
          _autoPausedSourcePartitions.put(tp, new PausedSourcePartitionMetadata(
              () -> _flushlessProducer.getInFlightCount(topic, partition) <= _minInFlightMessagesThreshold,
              PausedSourcePartitionMetadata.Reason.EXCEEDED_MAX_IN_FLIGHT_MSG_THRESHOLD));
          _taskUpdates.add(DatastreamConstants.UpdateType.PAUSE_RESUME_PARTITIONS);
          _flowControlTriggerCount++;
        }
      }
    } else {
      super.sendDatastreamProducerRecord(datastreamProducerRecord);
    }
  }

  @Override
  public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
    super.onPartitionsAssigned(partitions);
    Collection<TopicPartition> topicPartitionsToPause = _topicManager.onPartitionsAssigned(partitions);
    // we need to explicitly pause these partitions here and not wait for PreConsumerPollHook.
    // The reason being onPartitionsAssigned() gets called as part of kafka poll, so we need to pause partitions
    // before that poll call can return any data.
    // If we let partitions be paused as part of pre-poll hook, that will happen in the next poll cycle.
    // Chances are that the current poll call will return data already for that topic/partition and we will end up
    // auto creating that topic.
    pauseTopicManagerPartitions(topicPartitionsToPause);
  }

  @Override
  public void stop() {
    super.stop();
    _topicManager.stop();
  }

  @Override
  public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
    super.onPartitionsRevoked(partitions);
    _topicManager.onPartitionsRevoked(partitions);
  }

  private void commitSafeOffsets(Consumer<?, ?> consumer) {
    LOG.info("Trying to commit safe offsets.");
    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
    for (TopicPartition tp : consumer.assignment()) {
      // add 1 to the last acked checkpoint to set to the offset of the next message to consume
      _flushlessProducer.getAckCheckpoint(tp.topic(), tp.partition())
          .ifPresent(o -> offsets.put(tp, new OffsetAndMetadata(o + 1)));
    }
    commitWithRetries(consumer, Optional.of(offsets));
    _lastCommittedTime = System.currentTimeMillis();
  }

  @Override
  protected void maybeCommitOffsets(Consumer<?, ?> consumer, boolean hardCommit) {
    if (_isFlushlessModeEnabled) {
      boolean isTimeToCommit = System.currentTimeMillis() - _lastCommittedTime > _offsetCommitInterval;
      if (hardCommit) { // hard commit (flush and commit checkpoints)
        LOG.info("Calling flush on the producer.");
        _datastreamTask.getEventProducer().flush();
        commitSafeOffsets(consumer);

        // clear the flushless producer state after flushing all messages and checkpointing
        _flushlessProducer.clear();
      } else if (isTimeToCommit) { // soft commit (no flush, just commit checkpoints)
        commitSafeOffsets(consumer);
      }
    } else {
      super.maybeCommitOffsets(consumer, hardCommit);
    }
  }

  public static List<BrooklinMetricInfo> getMetricInfos(String connectorName) {
    return AbstractKafkaBasedConnectorTask.getMetricInfos(
        generateMetricsPrefix(connectorName, CLASS_NAME) + MetricsAware.KEY_REGEX);
  }

  @VisibleForTesting
  public static String getMirrorMakerGroupId(DatastreamTask task, GroupIdConstructor groupIdConstructor,
      CommonConnectorMetrics consumerMetrics, Logger logger) {
    try {
      return groupIdConstructor.getTaskGroupId(task, Optional.of(logger));
    } catch (Exception e) {
      consumerMetrics.updateErrorRate(1, "Can't find group ID", e);
      throw e;
    }
  }

  /**
   * This method pauses topics that are returned by topic manager.
   * We need to explicitly pause these partitions here and not wait for PreConsumerPollHook.
   * The reason being onPartitionsAssigned() gets called as part of kafka poll, so we need to pause partitions
   * before that poll call can return any data.
   * If we let partitions be paused as part of pre-poll hook, that will happen in the next poll cycle.
   * Chances are that the current poll call will return data already for that topic/partition and we will end up
   * auto creating that topic.
   */
  private void pauseTopicManagerPartitions(Collection<TopicPartition> topicManagerPartitions) {
    if (null == topicManagerPartitions || topicManagerPartitions.isEmpty()) {
      return;
    }

    _consumer.pause(topicManagerPartitions);
    for (TopicPartition tp : topicManagerPartitions) {
      _autoPausedSourcePartitions.put(tp,
          new PausedSourcePartitionMetadata(() -> _topicManager.shouldResumePartition(tp),
              PausedSourcePartitionMetadata.Reason.TOPIC_NOT_CREATED));
    }
  }

  @Override
  public KafkaDatastreamStatesResponse getKafkaDatastreamStatesResponse() {
    return new KafkaDatastreamStatesResponse(_datastreamName, _autoPausedSourcePartitions, _pausedPartitionsConfig,
        _consumerAssignment,
        _isFlushlessModeEnabled ? _flushlessProducer.getInFlightMessagesCounts() : Collections.emptyMap());
  }

  @VisibleForTesting
  long getInFlightMessagesCount(String source, int partition) {
    return _isFlushlessModeEnabled ? _flushlessProducer.getInFlightCount(source, partition) : 0;
  }

  @VisibleForTesting
  int getFlowControlTriggerCount() {
    return _flowControlTriggerCount;
  }

  @VisibleForTesting
  public TopicManager getTopicManager() {
    return _topicManager;
  }
}
