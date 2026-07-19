package com.coffeepointordersystem.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
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

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3})
	void popularMenuListener_assignsAllPartitionsForConfiguredConcurrency(int concurrency) {
		String groupId = "consumer-scaling-" + UUID.randomUUID();

		createContextRunner(groupId, concurrency, true)
				.run(context -> {
					KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);
					MessageListenerContainer listenerContainer = registry.getListenerContainers().iterator().next();

					assertThat(listenerContainer).isInstanceOf(ConcurrentMessageListenerContainer.class);
					assertThat(((ConcurrentMessageListenerContainer<?, ?>) listenerContainer).getConcurrency())
							.isEqualTo(concurrency);
					assertPartitionAssignment(groupId, concurrency);
				});
	}

	@Test
	void popularMenuListener_doesNotStartWhenDisabled() {
		String groupId = "consumer-scaling-" + UUID.randomUUID();

		createContextRunner(groupId, 1, false)
				.run(context -> {
					KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);
					MessageListenerContainer listenerContainer = registry.getListenerContainers().iterator().next();

					assertThat(listenerContainer.isRunning()).isFalse();
				});
	}

	private ApplicationContextRunner createContextRunner(String groupId, int concurrency, boolean enabled) {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
				.withUserConfiguration(ListenerTestConfig.class)
				.withPropertyValues(
						"spring.kafka.consumer.group-id=" + groupId,
						"popular-menu.consumer.concurrency=" + concurrency,
						"popular-menu.consumer.enabled=" + enabled
				);
	}

	private void createOrderCompletedTopic() throws Exception {
		OrderCompletedKafkaTopicConfig config = new OrderCompletedKafkaTopicConfig();

		try (AdminClient adminClient = createAdminClient()) {
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

	private void assertPartitionAssignment(String groupId, int expectedActiveConsumerCount) {
		Instant deadline = Instant.now().plus(ASSIGNMENT_TIMEOUT);
		Exception lastException = null;

		while (Instant.now().isBefore(deadline)) {
			try (AdminClient adminClient = createAdminClient()) {
				ConsumerGroupDescription description = adminClient.describeConsumerGroups(List.of(groupId))
						.all()
						.get(1L, TimeUnit.SECONDS)
						.get(groupId);
				List<MemberDescription> members = List.copyOf(description.members());
				Set<TopicPartition> assignedPartitions = members.stream()
						.flatMap(member -> member.assignment().topicPartitions().stream())
						.filter(topicPartition -> topicPartition.topic()
								.equals(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC))
						.collect(java.util.stream.Collectors.toSet());
				long activeConsumerCount = members.stream()
						.filter(member -> member.assignment().topicPartitions().stream()
								.anyMatch(topicPartition -> topicPartition.topic()
										.equals(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC)))
						.count();

				if (activeConsumerCount == expectedActiveConsumerCount
						&& assignedPartitions.size() == OrderCompletedKafkaTopicConfig.PARTITION_COUNT) {
					return;
				}
			} catch (Exception exception) {
				lastException = exception;
			}

			Thread.onSpinWait();
		}

		throw new IllegalStateException("Kafka Consumer Group partition assignment did not complete in time.", lastException);
	}

	private AdminClient createAdminClient() {
		return AdminClient.create(Map.of(
				AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()
		));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableKafka
	@Import({PopularMenuKafkaConsumerConfig.class, PopularMenuKafkaConsumer.class})
	static class ListenerTestConfig {

		@Bean
		ConsumerFactory<String, OrderCompletedEvent> consumerFactory(Environment environment) {
			return new DefaultKafkaConsumerFactory<>(Map.of(
					ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
					KAFKA.getBootstrapServers(),
					ConsumerConfig.GROUP_ID_CONFIG,
					environment.getRequiredProperty("spring.kafka.consumer.group-id"),
					ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
					StringDeserializer.class,
					ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
					StringDeserializer.class,
					ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
					"latest"
			));
		}

		@Bean
		PopularMenuCache popularMenuCache() {
			return mock(PopularMenuCache.class);
		}

	}

}
