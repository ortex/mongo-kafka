/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mongodb.kafka.connect;

import static com.mongodb.kafka.connect.mongodb.ChangeStreamOperations.concat;
import static com.mongodb.kafka.connect.mongodb.ChangeStreamOperations.createDropCollection;
import static com.mongodb.kafka.connect.mongodb.ChangeStreamOperations.createInserts;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.OUTPUT_FORMAT_KEY_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.OUTPUT_FORMAT_VALUE_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.OUTPUT_SCHEMA_KEY_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.OUTPUT_SCHEMA_VALUE_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.PIPELINE_CONFIG;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.rangeClosed;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Properties;
import java.util.stream.StreamSupport;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.connect.json.JsonDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.bson.Document;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import com.mongodb.kafka.connect.mongodb.MongoKafkaTestCase;
import com.mongodb.kafka.connect.source.MongoSourceConfig;
import com.mongodb.kafka.connect.source.MongoSourceConfig.OutputFormat;

import com.fasterxml.jackson.databind.JsonNode;

public class MongoSourceConnectorTest extends MongoKafkaTestCase {

  @BeforeEach
  void setUp() {
    assumeTrue(isReplicaSetOrSharded());
    cleanUp();
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  @DisplayName("Ensure source loads data from MongoClient")
  void testSourceLoadsDataFromMongoClient() {
    assumeTrue(isGreaterThanThreeDotSix());
    addSourceConnector();

    MongoDatabase db1 = getDatabaseWithPostfix();
    MongoDatabase db2 = getDatabaseWithPostfix();
    MongoDatabase db3 = getDatabaseWithPostfix();
    MongoCollection<Document> coll1 = db1.getCollection("coll");
    MongoCollection<Document> coll2 = db2.getCollection("coll");
    MongoCollection<Document> coll3 = db3.getCollection("coll");
    MongoCollection<Document> coll4 = db1.getCollection("db1Coll2");

    insertMany(rangeClosed(1, 50), coll1, coll2);

    db1.drop();
    sleep();
    insertMany(rangeClosed(51, 60), coll2, coll4);
    insertMany(rangeClosed(1, 70), coll3);

    assertAll(
        () ->
            assertProduced(
                concat(createInserts(1, 50), singletonList(createDropCollection())), coll1),
        () -> assertProduced(createInserts(1, 60), coll2),
        () -> assertProduced(createInserts(1, 70), coll3),
        () -> assertProduced(createInserts(51, 60), coll4));
  }

  @Test
  @DisplayName("Ensure source loads data from MongoClient with copy existing data")
  void testSourceLoadsDataFromMongoClientWithCopyExisting() {
    assumeTrue(isGreaterThanThreeDotSix());
    Properties sourceProperties = new Properties();
    sourceProperties.put(MongoSourceConfig.COPY_EXISTING_CONFIG, "true");
    addSourceConnector(sourceProperties);

    MongoDatabase db1 = getDatabaseWithPostfix();
    MongoDatabase db2 = getDatabaseWithPostfix();
    MongoDatabase db3 = getDatabaseWithPostfix();
    MongoCollection<Document> coll1 = db1.getCollection("coll");
    MongoCollection<Document> coll2 = db2.getCollection("coll");
    MongoCollection<Document> coll3 = db3.getCollection("coll");
    MongoCollection<Document> coll4 = db1.getCollection("db1Coll2");

    insertMany(rangeClosed(1, 50), coll1, coll2);
    db1.drop();
    sleep();
    insertMany(rangeClosed(51, 60), coll2, coll4);
    insertMany(rangeClosed(1, 70), coll3);

    assertAll(
        () ->
            assertProduced(
                concat(createInserts(1, 50), singletonList(createDropCollection())), coll1),
        () -> assertProduced(createInserts(1, 60), coll2),
        () -> assertProduced(createInserts(1, 70), coll3),
        () -> assertProduced(createInserts(51, 60), coll4));
  }

  @Test
  @DisplayName("Ensure source loads data from collection with copy existing data - outputting json")
  void testSourceLoadsDataFromCollectionCopyExistingJson() {
    assumeTrue(isGreaterThanFourDotZero());
    MongoCollection<Document> coll = getAndCreateCollection();

    insertMany(rangeClosed(1, 50), coll);

    Properties sourceProperties = new Properties();
    sourceProperties.put(MongoSourceConfig.DATABASE_CONFIG, coll.getNamespace().getDatabaseName());
    sourceProperties.put(
        MongoSourceConfig.COLLECTION_CONFIG, coll.getNamespace().getCollectionName());
    sourceProperties.put(MongoSourceConfig.COPY_EXISTING_CONFIG, "true");
    addSourceConnector(sourceProperties);

    insertMany(rangeClosed(51, 100), coll);
    assertProduced(createInserts(1, 100), coll);
  }

  @Test
  @DisplayName("Ensure source loads data from collection with copy existing data - outputting bson")
  void testSourceLoadsDataFromCollectionCopyExistingBson() {
    assumeTrue(isGreaterThanFourDotZero());
    MongoCollection<Document> coll = getAndCreateCollection();

    insertMany(rangeClosed(1, 50), coll);

    Properties sourceProperties = new Properties();
    sourceProperties.put(MongoSourceConfig.DATABASE_CONFIG, coll.getNamespace().getDatabaseName());
    sourceProperties.put(
        MongoSourceConfig.COLLECTION_CONFIG, coll.getNamespace().getCollectionName());
    sourceProperties.put(MongoSourceConfig.COPY_EXISTING_CONFIG, "true");
    sourceProperties.put(OUTPUT_FORMAT_KEY_CONFIG, OutputFormat.BSON.name());
    sourceProperties.put(MongoSourceConfig.OUTPUT_FORMAT_VALUE_CONFIG, OutputFormat.BSON.name());
    sourceProperties.put("key.converter", "org.apache.kafka.connect.converters.ByteArrayConverter");
    sourceProperties.put(
        "value.converter", "org.apache.kafka.connect.converters.ByteArrayConverter");

    addSourceConnector(sourceProperties);

    insertMany(rangeClosed(51, 100), coll);
    assertProduced(createInserts(1, 100), coll, OutputFormat.BSON);
  }

  @Test
  @DisplayName("Ensure Schema Key and Value output")
  void testSchemaKeyAndValueOutput() {
    assumeTrue(isGreaterThanFourDotZero());
    MongoCollection<Document> coll = getDatabaseWithPostfix().getCollection("coll");
    insertMany(rangeClosed(1, 10), coll);

    Properties sourceProperties = new Properties();
    sourceProperties.put(MongoSourceConfig.DATABASE_CONFIG, coll.getNamespace().getDatabaseName());
    sourceProperties.put(MongoSourceConfig.COPY_EXISTING_CONFIG, "true");

    sourceProperties.put(OUTPUT_FORMAT_KEY_CONFIG, OutputFormat.SCHEMA.name());
    sourceProperties.put(
        OUTPUT_SCHEMA_KEY_CONFIG,
        "{\"type\" : \"record\", \"name\" : \"key\","
            + "\"fields\" : [{\"name\": \"key\", \"type\": [\"int\",  \"null\"]}]}");
    sourceProperties.put(OUTPUT_FORMAT_VALUE_CONFIG, OutputFormat.SCHEMA.name());
    sourceProperties.put(
        OUTPUT_SCHEMA_VALUE_CONFIG,
        "{\"type\" : \"record\", \"name\" : \"fullDocument\","
            + "\"fields\" : [{\"name\": \"value\", \"type\": [\"int\",  \"null\"]}]}");
    sourceProperties.put("key.converter", "org.apache.kafka.connect.json.JsonConverter");
    sourceProperties.put("key.converter.schemas.enable", "false");
    sourceProperties.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
    sourceProperties.put("value.converter.schemas.enable", "false");
    sourceProperties.put(
        PIPELINE_CONFIG,
        "[{\"$addFields\": {\"key\": \"$fullDocument._id\", "
            + "\"value\": \"$fullDocument._id\"}}]");
    addSourceConnector(sourceProperties);

    List<ConsumerRecord<Integer, Integer>> expected =
        rangeClosed(1, 10)
            .boxed()
            .map(i -> new ConsumerRecord<>(coll.getNamespace().getFullName(), 0, 0, i, i))
            .collect(toList());

    Deserializer<Integer> deserializer = new KeyValueDeserializer();
    List<ConsumerRecord<Integer, Integer>> produced =
        getProduced(
            coll.getNamespace().getFullName(),
            deserializer,
            deserializer,
            cr ->
                new ConsumerRecord<>(coll.getNamespace().getFullName(), 0, 0, cr.key(), cr.value()),
            expected,
            10);

    assertIterableEquals(
        produced.stream().map(ConsumerRecord::key).collect(toList()),
        expected.stream().map(ConsumerRecord::key).collect(toList()));
    assertIterableEquals(
        produced.stream().map(ConsumerRecord::value).collect(toList()),
        expected.stream().map(ConsumerRecord::value).collect(toList()));
  }

  public static class KeyValueDeserializer implements Deserializer<Integer> {
    static final JsonDeserializer JSON_DESERIALIZER = new JsonDeserializer();

    @Override
    public Integer deserialize(final String topic, final byte[] data) {
      JsonNode node = JSON_DESERIALIZER.deserialize(topic, data);
      Iterable<String> iterable = node::fieldNames;
      List<String> fieldNames =
          StreamSupport.stream(iterable.spliterator(), false).collect(toList());
      if (fieldNames.contains("key")) {
        return node.get("key").asInt();
      } else if (fieldNames.contains("value")) {
        return node.get("value").asInt();
      }
      return -1;
    };
  }
}
