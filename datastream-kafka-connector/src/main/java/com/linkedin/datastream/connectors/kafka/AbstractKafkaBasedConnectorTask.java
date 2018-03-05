package com.linkedin.datastream.connectors.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.Validate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;

import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamConstants;
import com.linkedin.datastream.common.DatastreamMetadataConstants;
import com.linkedin.datastream.common.DatastreamRuntimeException;
import com.linkedin.datastream.common.DatastreamUtils;
import com.linkedin.datastream.common.JsonUtils;
import com.linkedin.datastream.common.VerifiableProperties;
import com.linkedin.datastream.connectors.CommonConnectorMetrics;
import com.linkedin.datastream.server.DatastreamEventProducer;
import com.linkedin.datastream.server.DatastreamProducerRecord;
import com.linkedin.datastream.server.DatastreamTask;
import com.linkedin.datastream.server.DatastreamTaskStatus;


/**
 * Base class for connector task, where the connector is Kafka-based. This base class provides basic structure for
 * Kafka-based connectors, which usually have similar processing pattern: subscribe() then run-loop to poll() and
 * process(). This also handles managing metrics as well as checkpointing at a configurable offset commit interval.
 */
abstract public class AbstractKafkaBasedConnectorTask implements Runnable, ConsumerRebalanceListener {

  protected final Logger _logger;

  protected static final String PROCESSING_DELAY_LOG_THRESHOLD_MS = "processingDelayLogThreshold";
  protected static final long DEFAULT_PROCESSING_DELAY_LOG_THRESHOLD_MS = Duration.ofMinutes(1).toMillis();
  protected final long _processingDelayLogThresholdMs;
  protected long _lastCommittedTime = System.currentTimeMillis();
  protected int _eventsProcessedCount = 0;
  protected static final Duration LOG_EVENTS_PROCESSED_PROGRESS_DURATION = Duration.ofMinutes(1);
  protected Instant _eventsProcessedCountLoggedTime;
  private static final long POLL_BUFFER_TIME_MS = 1000;

  protected final Properties _consumerProps;
  protected final String _datastreamName;
  protected final Datastream _datastream;

  // lifecycle
  protected volatile boolean _shouldDie = false;
  protected final CountDownLatch _startedLatch = new CountDownLatch(1);
  protected final CountDownLatch _stoppedLatch = new CountDownLatch(1);

  // config
  protected DatastreamTask _datastreamTask;
  protected final long _offsetCommitInterval;
  protected final Duration _retrySleepDuration;
  protected final int _maxRetryCount;
  protected boolean _pausePartitionOnError;
  protected final Optional<Map<Integer, Long>> _startOffsets;
  protected static final String CONSUMER_AUTO_OFFSET_RESET_CONFIG_LATEST = "latest";
  protected static final String CONSUMER_AUTO_OFFSET_RESET_CONFIG_EARLIEST = "earliest";

  // state
  protected volatile Thread _thread;
  protected volatile String _taskName;
  protected DatastreamEventProducer _producer;
  protected Consumer<?, ?> _consumer;

  // queue of Datastream task updates that need to be processed
  protected final ConcurrentLinkedQueue<DatastreamConstants.UpdateType> _taskUpdates =
      new ConcurrentLinkedQueue<>();
  @VisibleForTesting
  int _pausedPartitionsConfigUpdateCount = 0;
  // paused partitions config contains the topic partitions that are configured for pause (via Datastream metadata)
  protected Map<String, Set<String>> _pausedPartitionsConfig = new ConcurrentHashMap<>();
  // auto paused partitions set contains partitions that were paused on the fly (due to error or in-flight msg count)
  protected Set<TopicPartition> _autoPausedSourcePartitions = Sets.newConcurrentHashSet();

  protected CommonConnectorMetrics _consumerMetrics;

  protected AbstractKafkaBasedConnectorTask(Properties consumerProps, DatastreamTask task, long commitIntervalMillis,
      Duration retrySleepDuration, int maxRetryCount, boolean pausePartitionOnError, Logger logger) {
    _logger = logger;
    _logger.info(
        "Creating Kafka-based connector task for datastream task {} with commit interval {} ms, retry sleep duration {}"
            + " ms, retry count {}, pausePartitionOnSendError {}", task, commitIntervalMillis,
        retrySleepDuration.toMillis(), maxRetryCount, pausePartitionOnError);
    _datastreamTask = task;
    _producer = task.getEventProducer();
    _datastream = task.getDatastreams().get(0);
    _datastreamName = _datastream.getName();
    _taskName = task.getDatastreamTaskName();

    _consumerProps = consumerProps;
    VerifiableProperties properties = new VerifiableProperties(_consumerProps);
    _processingDelayLogThresholdMs = properties.containsKey(PROCESSING_DELAY_LOG_THRESHOLD_MS) ?
        properties.getLong(PROCESSING_DELAY_LOG_THRESHOLD_MS) : DEFAULT_PROCESSING_DELAY_LOG_THRESHOLD_MS;
    _maxRetryCount = maxRetryCount;
    _pausePartitionOnError = pausePartitionOnError;
    _startOffsets = Optional.ofNullable(_datastream.getMetadata().get(DatastreamMetadataConstants.START_POSITION))
        .map(json -> JsonUtils.fromJson(json, new TypeReference<Map<Integer, Long>>() {
        }));
    _offsetCommitInterval = commitIntervalMillis;
    _retrySleepDuration = retrySleepDuration;

    _consumerMetrics = createCommonConnectorMetrics(this.getClass().getSimpleName(), _datastreamName, _logger);
  }

  protected CommonConnectorMetrics createCommonConnectorMetrics(String className, String key, Logger errorLogger) {
    CommonConnectorMetrics consumerMetrics = new CommonConnectorMetrics(className, key, errorLogger);
    consumerMetrics.createEventProcessingMetrics();
    consumerMetrics.createPollMetrics();
    consumerMetrics.createPartitionMetrics();
    return consumerMetrics;
  }

  /**
   * Create a Kafka consumer using the consumer properties.
   * @param consumerProps the Kafka consumer properties
   * @return a KafkaConsumer
   */
  protected abstract Consumer<?, ?> createKafkaConsumer(Properties consumerProps);

  /**
   * Subscribe the consumer to a topic list or regex pattern and optionally set a callback (ConsumerRebalanceListener).
   */
  protected abstract void consumerSubscribe();

  /**
   * Translate the Kafka consumer record into a Datastream producer record.
   * @param fromKafka the Kafka consumer record
   * @param readTime the instant the record was polled from Kafka
   * @return a Datastream producer record
   */
  protected abstract DatastreamProducerRecord translate(ConsumerRecord<?, ?> fromKafka, Instant readTime) throws Exception;

  /**
   * Translate the Kafka consumer records if necessary and send the batch of records to destination.
   * @param records the Kafka consumer records
   * @param readTime the instant the records were successfully polled from the Kafka source
   */
  protected void translateAndSendBatch(ConsumerRecords<?, ?> records, Instant readTime) throws Exception {
    boolean shouldAddPausePartitionsTask = false;
    // iterate through each topic partition one at a time, for better isolation
    for (TopicPartition topicPartition : records.partitions()) {
      for (ConsumerRecord<?, ?> record : records.records(topicPartition)) {
        try {
          sendMessage(record, readTime);
        } catch (RuntimeException e) {
          _consumerMetrics.updateErrorRate(1);
          // seek to previous checkpoints for this topic partition
          seekToLastCheckpoint(Collections.singleton(topicPartition));
          if (_pausePartitionOnError) {
            // if configured to pause partition on error conditions, add to auto-paused set
            _logger.warn("Got exception while sending record {}, adding topic partition {} to auto-pause set", record,
                topicPartition);
            _autoPausedSourcePartitions.add(topicPartition);
            shouldAddPausePartitionsTask = true;
          }
          // skip other messages for this partition, but can continue processing other partitions
          break;
        }
      }
    }
    if (shouldAddPausePartitionsTask) {
      _taskUpdates.add(DatastreamConstants.UpdateType.PAUSE_RESUME_PARTITIONS);
    }
  }

  protected void sendMessage(ConsumerRecord<?, ?> record, Instant readTime) throws Exception {
    int sendAttempts = 0;
    while (true) {
      sendAttempts++;
      int numBytes = record.serializedKeySize() + record.serializedValueSize();
      _consumerMetrics.updateBytesProcessedRate(numBytes);
      DatastreamProducerRecord datastreamProducerRecord = translate(record, readTime);
      try {
        sendDatastreamProducerRecord(datastreamProducerRecord);
        return; // Break the retry loop and exit.
      } catch (Exception e) {
        _logger.error("Error sending Message. task: {} ; error: {};", _taskName, e.toString());
        _logger.error("Stack Trace: {}", Arrays.toString(e.getStackTrace()));
        if (_shouldDie || sendAttempts >= _maxRetryCount) {
          _logger.error("Send messages failed with exception: {}", e);
          throw e;
        }
        _logger.warn("Sleeping for {} seconds before retrying. Retry {} of {}",
            _retrySleepDuration.getSeconds(), sendAttempts, _maxRetryCount);
        Thread.sleep(_retrySleepDuration.toMillis());
      }
    }
  }

  protected void sendDatastreamProducerRecord(DatastreamProducerRecord datastreamProducerRecord) throws Exception {
    _producer.send(datastreamProducerRecord, null);
  }

  @Override
  public void run() {
    _logger.info("Starting the Kafka-based connector task for {}", _datastreamTask);
    boolean startingUp = true;
    long pollInterval = 0; // so 1st call to poll is fast for purposes of startup
    _thread = Thread.currentThread();

    _eventsProcessedCountLoggedTime = Instant.now();

    try {
      try (Consumer<?, ?> consumer = createKafkaConsumer(_consumerProps)) {
        _consumer = consumer;
        consumerSubscribe();

        ConsumerRecords<?, ?> records;
        while (!_shouldDie) {
          // perform any pre-computations before poll()
          preConsumerPollHook();

          // read a batch of records
          records = pollRecords(pollInterval);

          // handle startup notification if this is the 1st poll call
          if (startingUp) {
            pollInterval = getPollIntervalMs();
            startingUp = false;
            _startedLatch.countDown();
          }

          if (records != null && !records.isEmpty()) {
            Instant readTime = Instant.now();
            processRecords(records, readTime);
            trackEventsProcessedProgress(records.count());
          }
        } // end while loop

        // shutdown
        maybeCommitOffsets(consumer, true);
      }
    } catch (Exception e) {
      _logger.error("{} failed with exception.", _taskName, e);
      _datastreamTask.setStatus(DatastreamTaskStatus.error(e.getMessage()));
      throw new DatastreamRuntimeException(e);
    } finally {
      _stoppedLatch.countDown();
      _logger.info("{} stopped", _taskName);
    }
  }

  public void stop() {
    _logger.info("{} stopping", _taskName);
    _shouldDie = true;
    if (_consumer != null) {
      _consumer.wakeup();
    }
    _thread.interrupt();
  }

  public boolean awaitStart(long timeout, TimeUnit unit) throws InterruptedException {
    return _startedLatch.await(timeout, unit);
  }

  public boolean awaitStop(long timeout, TimeUnit unit) throws InterruptedException {
    return _stoppedLatch.await(timeout, unit);
  }

  protected void trackEventsProcessedProgress(int recordCount) {
    _eventsProcessedCount += recordCount;
    Duration timeSinceLastLog = Duration.between(_eventsProcessedCountLoggedTime, Instant.now());
    if (timeSinceLastLog.compareTo(LOG_EVENTS_PROCESSED_PROGRESS_DURATION) > 0) {
      _logger.info("Processed {} records in {} seconds for datastream {}", _eventsProcessedCount,
          timeSinceLastLog.getSeconds(), _datastreamName);
      _eventsProcessedCount = 0;
      _eventsProcessedCountLoggedTime = Instant.now();
    }
  }

  /**
   * Poll the records from Kafka using the specified timeout (milliseconds). If poll() fails and if retryCount is
   * configured, this method will sleep for some duration and try polling again.
   * @param pollInterval the timeout (in milliseconds) spent waiting in poll() if data is not available in buffer.
   * @return the Kafka consumer records polled
   * @throws InterruptedException if thread is interrupted during retry sleep
   */
  protected ConsumerRecords<?, ?> pollRecords(long pollInterval) throws InterruptedException {
    ConsumerRecords<?, ?> records;
    try {
      long curPollTime = System.currentTimeMillis();
      records = _consumer.poll(pollInterval);
      long pollDurationMs = System.currentTimeMillis() - curPollTime;
      if (pollDurationMs > pollInterval + POLL_BUFFER_TIME_MS) {
        // record poll time exceeding client poll timeout
        _logger.warn("ConsumerId: {}, Kafka client poll took {} ms (> poll timeout {} + buffer time {} ms)",
            _taskName, pollDurationMs, pollInterval, POLL_BUFFER_TIME_MS);
        _consumerMetrics.updateClientPollOverTimeout(1);
      }
      _consumerMetrics.updateNumPolls(1);
      _consumerMetrics.updateEventCountsPerPoll(records.count());
      if (!records.isEmpty()) {
        _consumerMetrics.updateEventsProcessedRate(records.count());
        _consumerMetrics.updateLastEventReceivedTime(Instant.now());
      }
      return records;
    } catch (NoOffsetForPartitionException e) {
      handleNoOffsetForPartitionException(e);
      return ConsumerRecords.EMPTY;
    } catch (WakeupException e) {
      _logger.warn("Got a WakeupException, shutdown in progress {}.", e);
      return ConsumerRecords.EMPTY;
    } catch (Exception e) {
      handlePollRecordsException(e);
      return ConsumerRecords.EMPTY;
    }
  }

  /**
   * Processes the Kafka consumer records by translating them and sending them to the event producer.
   * @param records the Kafka consumer records
   * @param readTime the time at which the records were successfully polled from Kafka
   */
  protected void processRecords(ConsumerRecords<?, ?> records, Instant readTime) {
    try {
      // send the batch out the other end
      // TODO(misanchez): we should have a way to signal the producer to stop and throw an exception
      //                  in case the _shouldDie signal is set (similar to kafka wakeup)
      translateAndSendBatch(records, readTime);

      if (System.currentTimeMillis() - readTime.toEpochMilli() > _processingDelayLogThresholdMs) {
        _consumerMetrics.updateProcessingAboveThreshold(1);
      }

      if (!_shouldDie) {
        // potentially commit our offsets (if its been long enough and all sends were successful)
        maybeCommitOffsets(_consumer, false);
      }
    } catch (Exception e) {
      handleProcessRecordsException(e);
    }
  }

  /**
   * Handle when Kafka consumer throws OffsetOutOfRangeException. The base behavior is no-op.
   * @param e the Exception
   */
  protected void handleOffsetOutOfRangeException(OffsetOutOfRangeException e) { }

  /**
   * Handle when Kafka consumer throws NoOffsetForPartitionException. The base behavior is to seek to start position
   * for all partitions.
   * @param e the Exception
   */
  protected void handleNoOffsetForPartitionException(NoOffsetForPartitionException e) {
    _logger.info("Poll threw NoOffsetForPartitionException for partitions {}.", e.partitions());
    if (!_shouldDie) {
      seekToStartPosition(_consumer, e.partitions());
    }
  }

  /**
   * Handle exception during pollRecords(). The base behavior is to sleep for some time.
   * @param e the Exception
   * @throws InterruptedException
   */
  protected void handlePollRecordsException(Exception e) throws InterruptedException {
    _logger.warn("Poll threw an exception. Sleeping for {} seconds and retrying. Exception: {}",
        _retrySleepDuration.getSeconds(), e);
    if (!_shouldDie) {
      Thread.sleep(_retrySleepDuration.toMillis());
    }
  }

  /**
   * Handle exception during processRecords(). The base behavior is to seek to previous checkpoints and retry on the
   * next poll.
   * @param e the Exception
   */
  protected void handleProcessRecordsException(Exception e) {
    _logger.info("Sending the messages failed with exception: {}", e);
    if (_shouldDie) {
      _logger.warn("Exiting without commit, no point to try to recover in shouldDie mode.");
      return; // return to skip the commit after the while loop
    }
    seekToLastCheckpoint(_consumer.assignment());
  }

  /**
   * @return the poll timeout (in milliseconds) to use in the Kafka consumer poll().
   */
  protected long getPollIntervalMs() {
    return _offsetCommitInterval / 2; // leave time for processing, assume 50-50
  }

  /**
   * Flush the producer and commit the offsets if the configured offset commit interval has been reached, or if
   * force is set to true.
   * @param consumer the Kafka consumer
   * @param force whether flush and commit should happen unconditionally
   */
  protected void maybeCommitOffsets(Consumer<?, ?> consumer, boolean force) {
    long now = System.currentTimeMillis();
    long timeSinceLastCommit = now - _lastCommittedTime;
    if (force || timeSinceLastCommit > _offsetCommitInterval) {
      _logger.info("Trying to flush the producer and commit offsets.");
      _producer.flush();
      consumer.commitSync();
      _lastCommittedTime = System.currentTimeMillis();
    }
  }

  /**
   * Seek to the last checkpoint for the given topicPartitions.
   */
  private void seekToLastCheckpoint(Set<TopicPartition> topicPartitions) {
    _logger.info("Trying to seek to previous checkpoint for partitions: {}", topicPartitions);
    Map<TopicPartition, OffsetAndMetadata> lastCheckpoint = new HashMap<>();
    Set<TopicPartition> tpWithNoCommits = new HashSet<>();
    // construct last checkpoint
    topicPartitions.forEach(tp -> {
      OffsetAndMetadata offset =  _consumer.committed(tp);
      // offset can be null if there was no prior commit
      if (offset == null) {
        tpWithNoCommits.add(tp);
      } else {
        lastCheckpoint.put(tp, _consumer.committed(tp));
      }
    });
    _logger.info("Seeking to previous checkpoints {}", lastCheckpoint);
    // reset consumer to last checkpoint
    lastCheckpoint.forEach((tp, offsetAndMetadata) -> _consumer.seek(tp, offsetAndMetadata.offset()));
    if (!tpWithNoCommits.isEmpty()) {
      _logger.info("Seeking to start position for partitions: {}", tpWithNoCommits);
      seekToStartPosition(_consumer, tpWithNoCommits);
    }
  }

  private void seekToStartPosition(Consumer<?, ?> consumer, Set<TopicPartition> partitions) {
    if (_startOffsets.isPresent()) {
      _logger.info("Datastream is configured with StartPosition. Trying to start from {}",
          _startOffsets.get());
      seekToOffset(consumer, partitions, _startOffsets.get());
    } else {
      // means we have no saved offsets for some partitions, seek to end or beginning based on consumer config
      if (CONSUMER_AUTO_OFFSET_RESET_CONFIG_LATEST.equals(
          _consumerProps.getProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
              CONSUMER_AUTO_OFFSET_RESET_CONFIG_LATEST))) {
        _logger.info("Datastream was not configured with StartPosition. Seeking to end for partitions: {}", partitions);
        consumer.seekToEnd(partitions);
      } else {
        _logger.info("Datastream was not configured with StartPosition. Seeking to beginning for partitions: {}",
            partitions);
        consumer.seekToBeginning(partitions);
      }
    }
  }

  private void seekToOffset(Consumer<?, ?> consumer, Set<TopicPartition> partitions,
      Map<Integer, Long> startOffsets) {
    partitions.forEach(tp -> {
      Long offset = startOffsets.get(tp.partition());
      if (offset == null) {
        String msg = String.format("Couldn't find the offset for partition %s", tp);
        _logger.error(msg);
        throw new DatastreamRuntimeException(msg);
      }
      consumer.seek(tp, offset);
    });

    _logger.info("Seek completed to the offsets.");
  }

  @Override
  public void onPartitionsRevoked(Collection<TopicPartition> topicPartitions) {
    _logger.info("Partition ownership revoked for {}, checkpointing.", topicPartitions);
    if (!_shouldDie) { // There is a commit at the end of the run method, skip extra commit in shouldDie mode.
      maybeCommitOffsets(_consumer, true); // happens inline as part of poll
    }

    // update paused partitions
    _taskUpdates.add(DatastreamConstants.UpdateType.PAUSE_RESUME_PARTITIONS);
  }

  @Override
  public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
    _consumerMetrics.updateRebalanceRate(1);
    _logger.info("Partition ownership assigned for {}.", partitions);

    // update paused partitions, in case.
    _taskUpdates.add(DatastreamConstants.UpdateType.PAUSE_RESUME_PARTITIONS);
  }

  /**
   * This method is called before making a call to poll() for any precomputations needed.
   *
   * Note: This method is supposed to be called from run() thread, so in effect, it is "single threaded".
   * The only updates that can happen to _taskUpdates queue outside this method is addition of
   * new update type when there is any update to datastream task (in method checkForUpdateTask())
   */
  protected void preConsumerPollHook() {
    // check if there was any update in task and take actions
    while (!_taskUpdates.isEmpty()) {
      DatastreamConstants.UpdateType updateType = _taskUpdates.remove();
      if (updateType != null) {
        switch (updateType) {
          case PAUSE_RESUME_PARTITIONS:
            pausePartitions();
            break;
          default:
            String msg = String.format("Unknown update type {} for task {}.", updateType, _taskName);
            _logger.error(msg);
            throw new DatastreamRuntimeException(msg);
        }
      }
    }
  }

  /**
   * The method, given a datastream task - checks if there is any update in the existing task.
   * @param datastreamTask - datastream task to compare with.
   */
  @VisibleForTesting
  public void checkForUpdateTask(DatastreamTask datastreamTask) {
    // check if there was any change in paused partitions.
    checkForPausedPartitionsUpdate(datastreamTask);
  }

  /**
   * Checks if there is any change in set of paused partitions for DatastreamTask.
   * @param datastreamTask the Datastream task
   */
  private void checkForPausedPartitionsUpdate(DatastreamTask datastreamTask) {
    // check if there is any change in paused partitions
    // first get the given set of paused source partitions from metadata
    Datastream datastream = datastreamTask.getDatastreams().get(0);
    Map<String, Set<String>> newPausedSourcePartitionsMap = DatastreamUtils.getDatastreamSourcePartitions(datastream);

    if (!newPausedSourcePartitionsMap.equals(_pausedPartitionsConfig)) {
      _logger.info(
          "Difference in partitions found in Datastream metadata paused partitions for datastream {}. The list is: {}. "
              + "Adding {} to taskUpdates queue", datastream, newPausedSourcePartitionsMap,
          DatastreamConstants.UpdateType.PAUSE_RESUME_PARTITIONS);
      _pausedPartitionsConfig.clear();
      _pausedPartitionsConfig.putAll(newPausedSourcePartitionsMap);
      _taskUpdates.add(DatastreamConstants.UpdateType.PAUSE_RESUME_PARTITIONS);
    }
  }

  private void pausePartitions() {
    _logger.info("Checking for partitions to pause or resume.");
    Validate.isTrue(_consumer != null, "Consumer cannot be null when pausing partitions.");
    _pausedPartitionsConfigUpdateCount++; // increment counter for testing purposes only

    // get the config that's already there on the consumer
    Set<TopicPartition> currentPausedPartitions = _consumer.paused();
    Set<TopicPartition> currentAssignedPartitions = _consumer.assignment();

    // print state
    _logger.info("Current partition assignment for task {} is: {}, and paused partitions are: {}", _taskName,
        currentAssignedPartitions, currentPausedPartitions);
    _logger.info("Current auto-pause partition set is: {}", _autoPausedSourcePartitions);

    // resume all paused partitions
    _consumer.resume(currentPausedPartitions);

    // pause the auto-paused and manually paused partitions that are assigned to this task
    Set<TopicPartition> partitionsToPause =
        determinePartitionsToPause(currentAssignedPartitions, _pausedPartitionsConfig, _autoPausedSourcePartitions);

    // keep the auto-paused set up to date with only assigned partitions
    _autoPausedSourcePartitions.retainAll(currentAssignedPartitions);

    if (!partitionsToPause.equals(currentPausedPartitions)) {
      // only log the new list if there was a change
      _logger.info("There were new partitions to pause. New partitions pause list is: {}", partitionsToPause);
    }

    // need to pause the entire new set of partitions to pause, since they were resumed at the beginning
    _logger.info("Full pause list is: {}", partitionsToPause);
    _consumer.pause(partitionsToPause);
  }

  /**
   * Determine which partitions to pause. A partition should be paused if: it is configured for pause (via Datastream
   * metadata) or it is set for auto-pause (because of error or high-throughput).
   *
   * WARNING: this method has a side-effect of removing from the auto-pause set any partition that exists in the map
   * of partitions that are configured for pause.
   * @param currentAssignment the set of topic partitions assigned to the consumer
   * @param configuredForPause a map of topic to partitions that are configured for pause
   * @param autoPause a set of topic partitions that are auto-paused
   * @return the total set of assigned partitions that need to be paused
   */
  protected static Set<TopicPartition> determinePartitionsToPause(Set<TopicPartition> currentAssignment,
      Map<String, Set<String>> configuredForPause, Set<TopicPartition> autoPause) {
    Set<TopicPartition> partitionsToPause = new HashSet<>();
    for (TopicPartition assigned : currentAssignment) {
      // if configured for pause, add to set
      if (isPartitionConfiguredForPause(assigned, configuredForPause)) {
        partitionsToPause.add(assigned);
      }

      // if set for auto-pause, add to the set
      if (autoPause.contains(assigned) && !partitionsToPause.add(assigned)) {
        // remove from auto pause set if partition is already configured for pause
        autoPause.remove(assigned);
      }
    }
    return partitionsToPause;
  }

  /**
   * If the set of paused partitions in the config contains all topics (denoted by REGEX_PAUSE_ALL_PARTITIONS_IN_A_TOPIC)
   * or contains the given partition explicitly, then the partition is configured to be paused.
   * @param topicPartition the topic partition
   * @param pauseConfiguration config map of topic to set of partitions to pause
   * @return true if the topic partition is configured to be paused.
   */
  private static boolean isPartitionConfiguredForPause(TopicPartition topicPartition,
      Map<String, Set<String>> pauseConfiguration) {
    Set<String> pausedPartitionsForTopic = pauseConfiguration.get(topicPartition.topic());
    return pausedPartitionsForTopic != null && (
        pausedPartitionsForTopic.contains(DatastreamMetadataConstants.REGEX_PAUSE_ALL_PARTITIONS_IN_A_TOPIC)
            || pausedPartitionsForTopic.contains(Integer.toString(topicPartition.partition())));
  }

  @VisibleForTesting
  public int getPausedPartitionsConfigUpdateCount() {
    return _pausedPartitionsConfigUpdateCount;
  }

  @VisibleForTesting
  public Set<TopicPartition> getAutoPausedSourcePartitions() {
    return Collections.unmodifiableSet(_autoPausedSourcePartitions);
  }

  @VisibleForTesting
  public Map<String, Set<String>> getPausedPartitionsConfig() {
    return Collections.unmodifiableMap(_pausedPartitionsConfig);
  }
}
