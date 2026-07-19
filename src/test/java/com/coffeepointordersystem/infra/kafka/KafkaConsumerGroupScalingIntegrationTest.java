package com.coffeepointordersystem.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class KafkaConsumerGroupScalingIntegrationTest {

	private static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(10L);
	private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka-native:3.8.0");

	@Container
	static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

	@BeforeEach
	void setUp() throws Exception {
		createOrderCompletedTopic();
	}

	@Test
	void oneConsumer_receivesAllThreePartitions() throws Exception {
		assertPartitionAssignment(1, 1);
	}

	@Test
	void twoConsumers_receiveAllThreePartitions() throws Exception {
		assertPartitionAssignment(2, 2);
	}

	@Test
	void threeConsumers_receiveOnePartitionEach() throws Exception {
		assertPartitionAssignment(3, 3);
	}

	@Test
	void fourConsumers_leaveOneConsumerIdleBecauseTheTopicHasThreePartitions() throws Exception {
		assertPartitionAssignment(4, OrderCompletedKafkaTopicConfig.PARTITION_COUNT);
	}

	private void createOrderCompletedTopic() throws Exception {
		OrderCompletedKafkaTopicConfig config = new OrderCompletedKafkaTopicConfig();

		try (AdminClient adminClient = AdminClient.create(Map.of(
				AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()
		))) {
			try {
				adminClient.createTopics(List.of(config.orderCompletedTopic())).all().get(10L, TimeUnit.SECONDS);
			} catch (ExecutionException exception) {
				if (!(exception.getCause() instanceof TopicExistsException)) {
					throw exception;
				}
			}

			int partitionCount = adminClient.describeTopics(List.of(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC))
					.allTopicNames()
					.get(10L, TimeUnit.SECONDS)
					.get(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC)
					.partitions()
					.size();
			assertThat(partitionCount).isEqualTo(OrderCompletedKafkaTopicConfig.PARTITION_COUNT);
		}
	}

	private void assertPartitionAssignment(int consumerCount, int expectedActiveConsumerCount) throws Exception {
		List<KafkaConsumer<String, String>> consumers = createConsumers(consumerCount);

		try {
			List<Set<TopicPartition>> assignments = awaitAssignments(consumers, expectedActiveConsumerCount);
			long activeConsumerCount = assignments.stream().filter(assignment -> !assignment.isEmpty()).count();
			Set<TopicPartition> assignedPartitions = assignments.stream()
					.flatMap(Set::stream)
					.collect(java.util.stream.Collectors.toSet());

			assertThat(activeConsumerCount).isEqualTo(expectedActiveConsumerCount);
			assertThat(assignedPartitions)
					.containsExactlyInAnyOrder(
							new TopicPartition(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC, 0),
							new TopicPartition(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC, 1),
							new TopicPartition(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC, 2)
					);
		} finally {
			for (KafkaConsumer<String, String> consumer : consumers) {
				consumer.close();
			}
		}
	}

	private List<KafkaConsumer<String, String>> createConsumers(int consumerCount) {
		String groupId = "consumer-scaling-" + UUID.randomUUID();
		List<KafkaConsumer<String, String>> consumers = new ArrayList<>();

		for (int index = 0; index < consumerCount; index++) {
			KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
					ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
					KAFKA.getBootstrapServers(),
					ConsumerConfig.GROUP_ID_CONFIG,
					groupId,
					ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
					"org.apache.kafka.common.serialization.StringDeserializer",
					ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
					"org.apache.kafka.common.serialization.StringDeserializer",
					ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
					"latest",
					ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
					false
			));
			consumer.subscribe(List.of(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC));
			consumers.add(consumer);
		}

		return consumers;
	}

	private List<Set<TopicPartition>> awaitAssignments(
			List<KafkaConsumer<String, String>> consumers,
			int expectedActiveConsumerCount
	) {
		Instant deadline = Instant.now().plus(ASSIGNMENT_TIMEOUT);
		while (Instant.now().isBefore(deadline)) {
			for (KafkaConsumer<String, String> consumer : consumers) {
				consumer.poll(Duration.ofMillis(100L));
			}

			List<Set<TopicPartition>> assignments = consumers.stream()
					.map(KafkaConsumer::assignment)
					.toList();
			int assignedPartitionCount = assignments.stream().mapToInt(Set::size).sum();
			long activeConsumerCount = assignments.stream().filter(assignment -> !assignment.isEmpty()).count();
			if (assignedPartitionCount == OrderCompletedKafkaTopicConfig.PARTITION_COUNT
					&& activeConsumerCount == expectedActiveConsumerCount) {
				return assignments;
			}
		}

		throw new IllegalStateException("Kafka Consumer Group partition assignment did not complete in time.");
	}

}
