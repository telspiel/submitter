package com.noesis.submitter.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.submitter.MessageSubmitter;

@Configuration
@EnableKafka
public class SubmitterConfig {

  @Value("${kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Value("${app.name}")
  private String appName;

  @Value("${kafka.max.poll.records.size}")
  private String maxPollRecordsSize;

  @Value("${kafka.auto.offset.reset.config}")
  private String kafkaAutoOffsetResetConfig;

  @Value("${kafka.submitter.topic.name}")
  private String kafkaSubmitterTopicName;
  
  @Value("${kafka.number.of.partitions.for.topic}")
  private String numberOfPartitionsForTopic;
  
  @Bean
  public Map<String, Object> consumerConfigs() {
    Map<String, Object> props = new HashMap<String, Object>();
    // list of host:port pairs used for establishing the initial connections to the Kafka cluster
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    //props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    // allows a pool of processes to divide the work of consuming and processing records
    props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaSubmitterTopicName);
    // automatically reset the offset to the earliest offset /latest
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaAutoOffsetResetConfig);
    //props.put(ConsumerConfig.CLIENT_ID_CONFIG, appName);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecordsSize);

    return props;
  }

  @Bean
  public ConsumerFactory<String, String> consumerFactory() {
	  return new DefaultKafkaConsumerFactory<>(consumerConfigs(), new StringDeserializer(),
		        new StringDeserializer());
  }

  @Bean
  public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, String>> kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory());
    factory.setBatchListener(true);
    factory.setConcurrency(Integer.parseInt(numberOfPartitionsForTopic));
    return factory;
  }

  @Bean
  public MessageSubmitter submitter() {
    return new MessageSubmitter(Integer.parseInt(maxPollRecordsSize));
  }

  @Bean
  public ObjectMapper objectMapper() {
	  return new ObjectMapper();
  }
}