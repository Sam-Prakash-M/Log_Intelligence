package com.ai.logintelligence.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.ai.logintelligence.config.PipelineProperties.Topics;

@Configuration
public class KafkaConfig {

	@Bean
	NewTopic rawLogsTopic(PipelineProperties props) {
		Topics t = props.topics();
		return TopicBuilder.name(t.rawLogs()).partitions(t.partitions()).replicas(t.replicas()).build();
	}

	@Bean
	NewTopic analysisRequestsTopic(PipelineProperties props) {
		Topics t = props.topics();
		return TopicBuilder.name(t.analysisRequests()).partitions(t.partitions()).replicas(t.replicas()).build();
	}

	@Bean
	NewTopic rawLogsDeadLetterTopic(PipelineProperties props) {
		return TopicBuilder.name(props.topics().rawLogsDeadLetter()).partitions(1)
				.replicas(props.topics().replicas()).build();
	}

	@Bean
	NewTopic analysisRequestsDeadLetterTopic(PipelineProperties props) {
		return TopicBuilder.name(props.topics().analysisRequestsDeadLetter()).partitions(1)
				.replicas(props.topics().replicas()).build();
	}

	/**
	 * Picked up by Spring Boot for every listener container: retry with exponential
	 * backoff (1s, 2s, 4s), then park the record on {@code <topic>.DLT} so one bad
	 * record can never block a partition.
	 */
	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
		var recoverer = new DeadLetterPublishingRecoverer(template,
				(rec, ex) -> new TopicPartition(rec.topic() + Topics.DEAD_LETTER_SUFFIX, -1));
		var backOff = new ExponentialBackOffWithMaxRetries(3);
		backOff.setInitialInterval(1_000);
		backOff.setMultiplier(2.0);
		return new DefaultErrorHandler(recoverer, backOff);
	}
}
