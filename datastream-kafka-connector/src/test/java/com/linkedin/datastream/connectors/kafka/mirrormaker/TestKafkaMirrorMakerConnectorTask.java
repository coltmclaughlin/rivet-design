package com.linkedin.datastream.connectors.kafka.mirrormaker;

import com.linkedin.datastream.common.JsonUtils;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.Charsets;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.TopicPartition;
import org.testng.Assert;
import org.testng.annotations.Test;

import kafka.admin.AdminUtils;
import kafka.utils.ZkUtils;

import com.linkedin.data.template.StringMap;
import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamDestination;
import com.linkedin.datastream.common.DatastreamMetadataConstants;
import com.linkedin.datastream.common.PollUtils;
import com.linkedin.datastream.connectors.kafka.BaseKafkaZkTest;
import com.linkedin.datastream.connectors.kafka.KafkaConsumerFactoryImpl;
import com.linkedin.datastream.connectors.kafka.MockDatastreamEventProducer;
import com.linkedin.datastream.server.DatastreamProducerRecord;
import com.linkedin.datastream.server.DatastreamTaskImpl;


public class TestKafkaMirrorMakerConnectorTask extends BaseKafkaZkTest {

  public Properties getKafkaProducerProperties() {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, _kafkaCluster.getBrokers());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.RETRIES_CONFIG, 100);
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
    props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
    props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getCanonicalName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getCanonicalName());

    return props;
  }

  private void produceEvents(String topic, int numEvents) {
    try (Producer<byte[], byte[]> producer = new KafkaProducer<>(getKafkaProducerProperties())) {
      for (int i = 0; i < numEvents; i++) {
        producer.send(
            new ProducerRecord<>(topic, ("key-" + i).getBytes(Charsets.UTF_8), ("value-" + i).getBytes(Charsets.UTF_8)),
            (metadata, exception) -> {
              if (exception != null) {
                throw new RuntimeException("Failed to send message.", exception);
              }
            });
      }
      producer.flush();
    }
  }

  private void createTopic(ZkUtils zkUtils, String topic) {
    if (!AdminUtils.topicExists(zkUtils, topic)) {
      AdminUtils.createTopic(zkUtils, topic, 1, 1, new Properties(), null);
    }
  }

  @Test
  public void testConsumeFromMultipleTopics() throws Exception {
    String yummyTopic = "YummyPizza";
    String saltyTopic = "SaltyPizza";
    String saladTopic = "HealthySalad";

    createTopic(_zkUtils, saladTopic);
    createTopic(_zkUtils, yummyTopic);
    createTopic(_zkUtils, saltyTopic);

    // create a datastream to consume from topics ending in "Pizza"
    StringMap metadata = new StringMap();
    metadata.put(DatastreamMetadataConstants.REUSE_EXISTING_DESTINATION_KEY, Boolean.FALSE.toString());
    Datastream datastream =
        TestKafkaMirrorMakerConnector.createDatastream("pizzaStream", _broker, "\\w+Pizza", metadata);
    datastream.setTransportProviderName("default");
    DatastreamDestination destination = new DatastreamDestination();
    destination.setConnectionString("%s");
    datastream.setDestination(destination);

    DatastreamTaskImpl task = new DatastreamTaskImpl(Collections.singletonList(datastream));
    MockDatastreamEventProducer datastreamProducer = new MockDatastreamEventProducer();
    task.setEventProducer(datastreamProducer);

    KafkaMirrorMakerConnectorTask connectorTask = createKafkaMirrorMakerConnectorTask(task);

    // produce an event to each of the 3 topics
    produceEvents(yummyTopic, 1);
    produceEvents(saltyTopic, 1);
    produceEvents(saladTopic, 1);

    if (!PollUtils.poll(() -> datastreamProducer.getEvents().size() == 2, 100, 25000)) {
      Assert.fail("did not transfer the msgs within timeout. transferred " + datastreamProducer.getEvents().size());
    }

    List<DatastreamProducerRecord> records = datastreamProducer.getEvents();
    for (DatastreamProducerRecord record : records) {
      String destinationTopic = record.getDestination().get();
      Assert.assertTrue(destinationTopic.endsWith("Pizza"),
          "Unexpected event consumed from Datastream and sent to topic: " + destinationTopic);
    }

    connectorTask.stop();
    Assert.assertTrue(connectorTask.awaitStop(5000, TimeUnit.MILLISECONDS), "did not shut down on time");
  }

  @Test
  public void testPauseAndResumePartitions() throws Exception {
    // Need connector just for update validation. Doesn't matter properties or datastream name
    KafkaMirrorMakerConnector connector =
        new KafkaMirrorMakerConnector("foo", new Properties());

    String yummyTopic = "YummyPizza";
    String saltyTopic = "SaltyPizza";
    String spicyTopic = "SpicyPizza";

    createTopic(_zkUtils, spicyTopic);
    createTopic(_zkUtils, yummyTopic);
    createTopic(_zkUtils, saltyTopic);

    // create a datastream to consume from topics ending in "Pizza"
    StringMap metadata = new StringMap();
    metadata.put(DatastreamMetadataConstants.REUSE_EXISTING_DESTINATION_KEY, Boolean.FALSE.toString());
    Datastream datastream =
        TestKafkaMirrorMakerConnector.createDatastream("pizzaStream", _broker, "\\w+Pizza", metadata);
    datastream.setTransportProviderName("default");
    DatastreamDestination destination = new DatastreamDestination();
    destination.setConnectionString("%s");
    datastream.setDestination(destination);

    DatastreamTaskImpl datastreamTask = new DatastreamTaskImpl(Collections.singletonList(datastream));
    MockDatastreamEventProducer datastreamProducer = new MockDatastreamEventProducer();
    datastreamTask.setEventProducer(datastreamProducer);

    KafkaMirrorMakerConnectorTask connectorTask = createKafkaMirrorMakerConnectorTask(datastreamTask);

    // Make sure there were 2 initial updates (by onPartitionsAssigned and onPartitionsRevoked)
    if (!PollUtils.poll(() -> connectorTask.getPausedPartitionsConfigUpdateCount() == 2, 100, 25000)) {
      Assert.fail("Paused partitions were not updated at the beginning. Expecting update count 1, found: "
          + connectorTask.getPausedPartitionsConfigUpdateCount());
    }

    // Make sure there isn't any paused partition
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig().size(), 0);

    // Produce an event to each of the 3 topics
    produceEvents(yummyTopic, 1);
    produceEvents(saltyTopic, 1);
    produceEvents(spicyTopic, 1);

    // Make sure all 3 events were read.
    if (!PollUtils.poll(() -> datastreamProducer.getEvents().size() == 3, 100, 25000)) {
      Assert.fail(
          "Did not transfer the msgs within timeout. Expected: 3 Tansferred: " + datastreamProducer.getEvents().size());
    }

    // Now create paused partitions
    // yummypizza - with partition 0
    // spicypizza - with all partitions ("*")
    Map<String, HashSet<String>> pausedPartitions = new HashMap<>();
    Map<String, HashSet<String>> expectedPartitions = new HashMap<>();
    pausedPartitions.put(yummyTopic, new HashSet<>(Collections.singletonList("0")));
    pausedPartitions.put(spicyTopic, new HashSet<>(Collections.singletonList("*")));
    datastream.getMetadata()
        .put(DatastreamMetadataConstants.PAUSED_SOURCE_PARTITIONS_KEY, JsonUtils.toJson(pausedPartitions));

    // Update connector task with paused partitions
    connector.validateUpdateDatastreams(Collections.singletonList(datastream), Collections.singletonList(datastream));
    connectorTask.checkForUpdateTask(datastreamTask);

    // Make sure there was an update, and that there paused partitions.
    if (!PollUtils.poll(() -> connectorTask.getPausedPartitionsConfigUpdateCount() == 3, 100, 25000)) {
      Assert.fail("Paused partitions were not updated. Expecting update count 3, found: "
          + connectorTask.getPausedPartitionsConfigUpdateCount());
    }

    // Make sure the paused partitions match.
    // prepare expectedPartitions for match
    expectedPartitions.put(yummyTopic, new HashSet<>(Collections.singletonList("0")));
    expectedPartitions.put(spicyTopic, new HashSet<>(Collections.singletonList("0")));
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig().size(), 2);
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig(), expectedPartitions);

    // Produce an event to each of the 3 topics
    produceEvents(yummyTopic, 1);
    produceEvents(saltyTopic, 1);
    produceEvents(spicyTopic, 1);

    // Make sure only 1 event was seen
    // Note: the mock producer doesn't delete previous messages by default, so the previously read records should also
    // be there.
    if (!PollUtils.poll(() -> datastreamProducer.getEvents().size() == 4, 100, 25000)) {
      Assert.fail(
          "Did not transfer the msgs within timeout. Expected: 4 Tansferred: " + datastreamProducer.getEvents().size());
    }

    // Now pause same set of partitions, and make sure there isn't any update.
    connectorTask.checkForUpdateTask(datastreamTask);
    if (!PollUtils.poll(() -> connectorTask.getPausedPartitionsConfigUpdateCount() == 3, 100, 25000)) {
      Assert.fail("Paused partitions were not updated. Expecting update count 3, found: "
          + connectorTask.getPausedPartitionsConfigUpdateCount());
    }
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig().size(), 2);
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig(), expectedPartitions);
    if (!PollUtils.poll(() -> datastreamProducer.getEvents().size() == 4, 100, 25000)) {
      Assert.fail(
          "Transferred msgs when not expected. Expected: 4 Tansferred:  " + datastreamProducer.getEvents().size());
    }

    // Now add * to yummypizza and 0 to spicy pizza, and make sure 0 is neglected for spicypizza and a * is added for yummypizza
    // As * will translate to all partitions, and yummypizza has only 1 partition which is already added, this will be a noop
    pausedPartitions.clear();
    pausedPartitions.put(yummyTopic, new HashSet<>(Collections.singletonList("*")));
    pausedPartitions.put(spicyTopic, new HashSet<>(Collections.singletonList("0")));
    datastream.getMetadata()
        .put(DatastreamMetadataConstants.PAUSED_SOURCE_PARTITIONS_KEY, JsonUtils.toJson(pausedPartitions));
    connector.validateUpdateDatastreams(Collections.singletonList(datastream), Collections.singletonList(datastream));
    connectorTask.checkForUpdateTask(datastreamTask);
    if (!PollUtils.poll(() -> connectorTask.getPausedPartitionsConfigUpdateCount() == 3, 100, 25000)) {
      Assert.fail("Paused partitions were not updated. Expecting update count 3, found: "
          + connectorTask.getPausedPartitionsConfigUpdateCount());
    }
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig().size(), 2);
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig(), expectedPartitions);
    // Make sure other 1 extra event was read
    // Note: the mock producer doesn't delete previous messages by default, so the previously read records should also
    // be there.
    if (!PollUtils.poll(() -> datastreamProducer.getEvents().size() == 4, 100, 25000)) {
      Assert.fail("Transferred messages when not expected, after * partitions were added. Expected: 4 Transferred: "
          + datastreamProducer.getEvents().size());
    }

    // Now update partition assignment
    // Doesn't matter the partition/topic - we just want to ensure paused partitions are updated (to the same value)
    connectorTask.onPartitionsAssigned(Collections.singletonList(new TopicPartition("randomTopic", 0)));
    if (!PollUtils.poll(() -> connectorTask.getPausedPartitionsConfigUpdateCount() == 4, 100, 25000)) {
      Assert.fail("Paused partitions were not updated. Expecting update count 4, found: "
          + connectorTask.getPausedPartitionsConfigUpdateCount());
    }
    // Expect no change in paused partitions
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig(), expectedPartitions);

    // Now resume both the partitions.
    datastream.getMetadata().put(DatastreamMetadataConstants.PAUSED_SOURCE_PARTITIONS_KEY, "");
    connector.validateUpdateDatastreams(Collections.singletonList(datastream), Collections.singletonList(datastream));
    connectorTask.checkForUpdateTask(datastreamTask);
    if (!PollUtils.poll(() -> connectorTask.getPausedPartitionsConfigUpdateCount() == 5, 100, 25000)) {
      Assert.fail("Paused partitions were not updated. Expecting update count 5, found: "
          + connectorTask.getPausedPartitionsConfigUpdateCount());
    }
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig().size(), 0);
    // Prepare expectedPartitions
    expectedPartitions.clear();
    Assert.assertEquals(connectorTask.getPausedPartitionsConfig(), expectedPartitions);
    // Make sure other 2 events were read
    // Note: the mock producer doesn't delete previous messages by default, so the previously read records should also
    // be there.
    if (!PollUtils.poll(() -> datastreamProducer.getEvents().size() == 6, 100, 25000)) {
      Assert.fail("Did not transfer the msgs within timeout. Expected: 6 Tansferred:  " + datastreamProducer.getEvents()
          .size());
    }

    connectorTask.stop();
    Assert.assertTrue(connectorTask.awaitStop(5000, TimeUnit.MILLISECONDS), "did not shut down on time");
  }

  @Test
  public void testAutoPauseOnSendFailure() throws Exception {
    String yummyTopic = "YummyPizza";
    createTopic(_zkUtils, yummyTopic);

    // create a datastream to consume from topics ending in "Pizza"
    StringMap metadata = new StringMap();
    metadata.put(DatastreamMetadataConstants.REUSE_EXISTING_DESTINATION_KEY, Boolean.FALSE.toString());
    Datastream datastream =
        TestKafkaMirrorMakerConnector.createDatastream("pizzaStream", _broker, "\\w+Pizza", metadata);
    datastream.setTransportProviderName("default");
    DatastreamDestination destination = new DatastreamDestination();
    destination.setConnectionString("%s");
    datastream.setDestination(destination);

    DatastreamTaskImpl task = new DatastreamTaskImpl(Collections.singletonList(datastream));
    // create event producer that fails on 3rd event (of 5)
    MockDatastreamEventProducer datastreamProducer =
        new MockDatastreamEventProducer((r) -> new String((byte[]) r.getEvents().get(0).key().get()).equals("key-2"));
    task.setEventProducer(datastreamProducer);

    Properties consumerProps = new Properties();
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    KafkaMirrorMakerConnectorTask connectorTask = createKafkaMirrorMakerConnectorTask(task, consumerProps);

    // produce 5 events
    produceEvents(yummyTopic, 5);

    // validate that the topic partition was added to auto-paused set
    if (!PollUtils.poll(() -> connectorTask.getAutoPausedSourcePartitions().contains(new TopicPartition(yummyTopic, 0)),
        100, 25000)) {
      Assert.fail("Partition did not auto-pause after multiple send failures.");
    }

    Assert.assertEquals(datastreamProducer.getEvents().size(), 2,
        "The events before the failure should have been sent");

    // resume the auto-paused partition by manually pausing and resuming
    // pause the partition
    Map<String, Set<String>> pausedPartitions = new HashMap<>();
    pausedPartitions.put(yummyTopic, new HashSet<>(Collections.singletonList("0")));
    datastream.getMetadata()
        .put(DatastreamMetadataConstants.PAUSED_SOURCE_PARTITIONS_KEY, JsonUtils.toJson(pausedPartitions));
    task = new DatastreamTaskImpl(Collections.singletonList(datastream));
    connectorTask.checkForUpdateTask(task);

    if (!PollUtils.poll(() -> pausedPartitions.equals(connectorTask.getPausedPartitionsConfig())
        && connectorTask.getAutoPausedSourcePartitions().isEmpty(), 100, 25000)) {
      Assert.fail("Paused partitions were not updated after adding partitions to pause config.");
    }

    // update the send failure condition so that events flow through once partition is resumed
    datastreamProducer.updateSendFailCondition((r) -> false);

    // resume the partition
    pausedPartitions.put(yummyTopic, Collections.emptySet());
    datastream.getMetadata()
        .put(DatastreamMetadataConstants.PAUSED_SOURCE_PARTITIONS_KEY, JsonUtils.toJson(pausedPartitions));
    task = new DatastreamTaskImpl(Collections.singletonList(datastream));
    connectorTask.checkForUpdateTask(task);

    if (!PollUtils.poll(() -> pausedPartitions.equals(connectorTask.getPausedPartitionsConfig()), 100, 25000)) {
      Assert.fail("Paused partitions were not updated after removing partitions from pause config.");
    }

    // verify that all the events got sent (the first 2 events got sent twice)
    if (!PollUtils.poll(() -> datastreamProducer.getEvents().size() == 7, 100, 25000)) {
      Assert.fail("datastream producer " + datastreamProducer.getEvents().size());
    }

    connectorTask.stop();
    Assert.assertTrue(connectorTask.awaitStop(5000, TimeUnit.MILLISECONDS), "did not shut down on time");
  }

  private KafkaMirrorMakerConnectorTask createKafkaMirrorMakerConnectorTask(DatastreamTaskImpl task)
      throws InterruptedException {
    return createKafkaMirrorMakerConnectorTask(task, new Properties());
  }

  private KafkaMirrorMakerConnectorTask createKafkaMirrorMakerConnectorTask(DatastreamTaskImpl task,
      Properties consumerConfig) throws InterruptedException {
    KafkaMirrorMakerConnectorTask connectorTask =
        new KafkaMirrorMakerConnectorTask(new KafkaConsumerFactoryImpl(), consumerConfig, task, 1000,
            Duration.ofSeconds(0), 5, true);
    Thread t = new Thread(connectorTask, "connector thread");
    t.setDaemon(true);
    t.setUncaughtExceptionHandler((t1, e) -> Assert.fail("connector thread died", e));
    t.start();
    if (!connectorTask.awaitStart(60, TimeUnit.SECONDS)) {
      Assert.fail("connector did not start within timeout");
    }
    return connectorTask;
  }
}
