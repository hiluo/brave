/*
 * Copyright 2013-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.kafka.clients;

import brave.internal.HexCodec;
import brave.messaging.MessagingRuleSampler;
import brave.messaging.MessagingTracing;
import brave.propagation.Propagation;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.propagation.TraceIdContext;
import brave.sampler.Sampler;
import brave.test.TestSpanReporter;
import com.github.charithe.kafka.EphemeralKafkaBroker;
import com.github.charithe.kafka.KafkaJunitRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.Span.Kind;
import zipkin2.internal.DependencyLinker;

import static brave.kafka.clients.KafkaTags.KAFKA_TOPIC_TAG;
import static brave.messaging.MessagingRequestMatchers.channelNameEquals;
import static brave.messaging.MessagingRequestMatchers.operationEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

public class ITKafkaTracing extends ITKafka {
  @ClassRule
  public static KafkaJunitRule kafkaRule = new KafkaJunitRule(EphemeralKafkaBroker.create());

  @Rule public TestSpanReporter producerReporter = new TestSpanReporter();
  @Rule public TestSpanReporter consumerReporter = new TestSpanReporter();

  KafkaTracing producerTracing = KafkaTracing.create(
    tracingBuilder(Sampler.ALWAYS_SAMPLE).localServiceName("producer")
      .spanReporter(producerReporter).build()
  );

  KafkaTracing consumerTracing = KafkaTracing.create(
    tracingBuilder(Sampler.ALWAYS_SAMPLE).localServiceName("consumer")
      .spanReporter(consumerReporter).build()
  );

  Producer<String, String> producer;
  Consumer<String, String> consumer;

  @After public void close() {
    if (producer != null) producer.close();
    if (consumer != null) consumer.close();
  }

  @Test public void poll_creates_one_consumer_span_per_extracted_context() {
    String topic1 = testName.getMethodName() + "1";
    String topic2 = testName.getMethodName() + "2";

    producer = createTracingProducer();
    consumer = createTracingConsumer(topic1, topic2);

    send(new ProducerRecord<>(topic1, TEST_KEY, TEST_VALUE));
    send(new ProducerRecord<>(topic2, TEST_KEY, TEST_VALUE));

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(2);
    Span producerSpan1 = takeProducerSpan(), producerSpan2 = takeProducerSpan();
    Span consumerSpan1 = takeConsumerSpan(), consumerSpan2 = takeConsumerSpan();

    // Check to see the trace is continued between the producer and the consumer
    // we don't know the order the spans will come in. Correlate with the tag instead.
    String firstTopic = producerSpan1.tags().get(KAFKA_TOPIC_TAG);
    if (firstTopic.equals(consumerSpan1.tags().get(KAFKA_TOPIC_TAG))) {
      assertThat(producerSpan1.traceId())
        .isEqualTo(consumerSpan1.traceId());
      assertThat(producerSpan2.traceId())
        .isEqualTo(consumerSpan2.traceId());
    } else {
      assertThat(producerSpan1.traceId())
        .isEqualTo(consumerSpan2.traceId());
      assertThat(producerSpan2.traceId())
        .isEqualTo(consumerSpan1.traceId());
    }
  }

  void send(ProducerRecord<String, String> record) {
    BlockingCallback callback = new BlockingCallback();
    producer.send(record, callback);
    callback.join();
  }

  Span takeProducerSpan() {
    return producerReporter.takeRemoteSpan(Kind.PRODUCER);
  }

  Span takeConsumerSpan() {
    return consumerReporter.takeRemoteSpan(Kind.CONSUMER);
  }

  @Test
  public void poll_creates_one_consumer_span_per_topic() {
    String topic1 = testName.getMethodName() + "1";
    String topic2 = testName.getMethodName() + "2";

    producer = kafkaRule.helper().createStringProducer(); // not traced
    consumer = createTracingConsumer(topic1, topic2);

    for (int i = 0; i < 5; i++) {
      send(new ProducerRecord<>(topic1, TEST_KEY, TEST_VALUE));
      send(new ProducerRecord<>(topic2, TEST_KEY, TEST_VALUE));
    }

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(10);

    takeConsumerSpan();
    takeConsumerSpan();
  }

  @Test
  public void creates_dependency_links() {
    producer = createTracingProducer();
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(testName.getMethodName(), TEST_KEY, TEST_VALUE));

    consumer.poll(10000);

    List<Span> allSpans = new ArrayList<>();
    allSpans.add(takeConsumerSpan());
    allSpans.add(takeProducerSpan());

    List<DependencyLink> links = new DependencyLinker().putTrace(allSpans).link();
    assertThat(links).extracting("parent", "child").containsExactly(
      tuple("producer", "kafka"),
      tuple("kafka", "consumer")
    );
  }

  @Test
  public void nextSpan_makes_child() {
    producer = createTracingProducer();
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(testName.getMethodName(), TEST_KEY, TEST_VALUE));

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(1);
    Span producerSpan = takeProducerSpan();
    Span consumerSpan = takeConsumerSpan();

    for (ConsumerRecord<String, String> record : records) {
      brave.Span processor = consumerTracing.nextSpan(record);

      assertThat(consumerSpan.tags())
        .containsEntry(KAFKA_TOPIC_TAG, record.topic());

      assertThat(processor.context().traceIdString()).isEqualTo(consumerSpan.traceId());
      assertThat(processor.context().parentIdString()).isEqualTo(consumerSpan.id());

      processor.start().name("processor").finish();

      // The processor doesn't taint the consumer span which has already finished
      Span processorSpan = consumerReporter.takeLocalSpan();
      assertThat(processorSpan.id())
        .isNotEqualTo(consumerSpan.id());
    }
  }

  static class TraceIdOnlyPropagation<K> implements Propagation<K> {
    final K key;

    TraceIdOnlyPropagation(Propagation.KeyFactory<K> keyFactory) {
      key = keyFactory.create("x-b3-traceid");
    }

    @Override public List<K> keys() {
      return Collections.singletonList(key);
    }

    @Override public <C> TraceContext.Injector<C> injector(Setter<C, K> setter) {
      return (traceContext, carrier) -> setter.put(carrier, key, traceContext.traceIdString());
    }

    @Override public <C> TraceContext.Extractor<C> extractor(Getter<C, K> getter) {
      return carrier -> {
        String result = getter.get(carrier, key);
        if (result == null) return TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY);
        return TraceContextOrSamplingFlags.create(TraceIdContext.newBuilder()
          .traceId(HexCodec.lowerHexToUnsignedLong(result))
          .build());
      };
    }
  }

  @Test
  public void continues_a_trace_when_only_trace_id_propagated() {
    consumerTracing = KafkaTracing.create(tracingBuilder(Sampler.ALWAYS_SAMPLE)
      .spanReporter(consumerReporter)
      .propagationFactory(new Propagation.Factory() {
        @Override public <K> Propagation<K> create(Propagation.KeyFactory<K> keyFactory) {
          return new TraceIdOnlyPropagation<>(keyFactory);
        }
      })
      .build());
    producerTracing = KafkaTracing.create(tracingBuilder(Sampler.ALWAYS_SAMPLE)
      .spanReporter(producerReporter)
      .propagationFactory(new Propagation.Factory() {
        @Override public <K> Propagation<K> create(Propagation.KeyFactory<K> keyFactory) {
          return new TraceIdOnlyPropagation<>(keyFactory);
        }
      })
      .build());

    producer = createTracingProducer();
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(testName.getMethodName(), TEST_KEY, TEST_VALUE));

    // intentionally using deprecated method as we are checking the same class in an invoker test
    // under src/it. If we want to explicitly tests the Duration arg, we will have to subclass.
    ConsumerRecords<String, String> records = consumer.poll(10_000L);

    assertThat(records).hasSize(1);
    Span producerSpan = takeProducerSpan();
    Span consumerSpan = takeConsumerSpan();

    assertThat(producerSpan.traceId())
      .isEqualTo(consumerSpan.traceId());

    for (ConsumerRecord<String, String> record : records) {
      TraceContext forProcessor = consumerTracing.nextSpan(record).context();

      assertThat(forProcessor.traceIdString()).isEqualTo(consumerSpan.traceId());
    }
  }

  @Test public void customSampler_producer() {
    String topic = testName.getMethodName();

    producerTracing = KafkaTracing.create(
      MessagingTracing.newBuilder(producerTracing.messagingTracing.tracing())
        .producerSampler(MessagingRuleSampler.newBuilder()
          .putRule(channelNameEquals(topic), Sampler.NEVER_SAMPLE)
          .build())
        .build());

    producer = createTracingProducer();
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(topic, TEST_KEY, TEST_VALUE));

    // intentionally using deprecated method as we are checking the same class in an invoker test
    // under src/it. If we want to explicitly tests the Duration arg, we will have to subclass.
    ConsumerRecords<String, String> records = consumer.poll(10_000L);

    assertThat(records).hasSize(1);
    checkB3Unsampled(records);

    // since the producer was unsampled, the consumer should be unsampled also due to propagation

    // @After will also check that both the producer and consumer were not sampled
  }

  void checkB3Unsampled(ConsumerRecords<String, String> records) {
    // Check that the injected context was not sampled
    assertThat(records)
      .extracting(ConsumerRecord::headers)
      .flatExtracting(TracingConsumerTest::lastHeaders)
      .hasSize(1)
      .allSatisfy(e -> {
        assertThat(e.getKey()).isEqualTo("b3");
        assertThat(e.getValue()).endsWith("-0");
      });
  }

  @Test public void customSampler_consumer() {
    String topic = testName.getMethodName();

    consumerTracing = KafkaTracing.create(
      MessagingTracing.newBuilder(consumerTracing.messagingTracing.tracing())
        .consumerSampler(MessagingRuleSampler.newBuilder()
          .putRule(operationEquals("receive"), Sampler.NEVER_SAMPLE)
          .build()).build());

    producer = kafkaRule.helper().createStringProducer(); // intentionally don't trace the producer
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(topic, TEST_KEY, TEST_VALUE));

    // intentionally using deprecated method as we are checking the same class in an invoker test
    // under src/it. If we want to explicitly tests the Duration arg, we will have to subclass.
    ConsumerRecords<String, String> records = consumer.poll(10_000L);

    assertThat(records).hasSize(1);
    checkB3Unsampled(records);

    // @After will also check that the consumer was not sampled
  }

  Consumer<String, String> createTracingConsumer(String... topics) {
    if (topics.length == 0) topics = new String[] {testName.getMethodName()};
    KafkaConsumer<String, String> consumer = kafkaRule.helper().createStringConsumer();
    List<TopicPartition> assignments = new ArrayList<>();
    for (String topic : topics) {
      assignments.add(new TopicPartition(topic, 0));
    }
    consumer.assign(assignments);
    return consumerTracing.consumer(consumer);
  }

  Producer<String, String> createTracingProducer() {
    KafkaProducer<String, String> producer = kafkaRule.helper().createStringProducer();
    return producerTracing.producer(producer);
  }
}
