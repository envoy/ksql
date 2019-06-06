/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.ddl.commands;

import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.ddl.DdlConfig;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.metastore.SerdeFactory;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.parser.tree.CreateSource;
import io.confluent.ksql.parser.tree.CreateSourceProperties;
import io.confluent.ksql.parser.tree.TableElement;
import io.confluent.ksql.schema.ksql.KsqlSchema;
import io.confluent.ksql.schema.ksql.LogicalSchemas;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.services.KafkaTopicClient;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.SchemaUtil;
import io.confluent.ksql.util.StringUtil;
import io.confluent.ksql.util.timestamp.TimestampExtractionPolicy;
import io.confluent.ksql.util.timestamp.TimestampExtractionPolicyFactory;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.streams.kstream.Windowed;

/**
 * Base class of create table/stream command
 */
abstract class CreateSourceCommand implements DdlCommand {

  final String sqlExpression;
  final String sourceName;
  final String topicName;
  final KsqlSchema schema;
  final KeyField keyField;
  final RegisterTopicCommand registerTopicCommand;
  private final KafkaTopicClient kafkaTopicClient;
  final SerdeFactory<?> keySerdeFactory;
  final TimestampExtractionPolicy timestampExtractionPolicy;
  private final Set<SerdeOption> serdeOptions;

  CreateSourceCommand(
      final String sqlExpression,
      final CreateSource statement,
      final KsqlConfig ksqlConfig,
      final KafkaTopicClient kafkaTopicClient
  ) {
    this.sqlExpression = sqlExpression;
    this.sourceName = statement.getName().getSuffix();
    this.kafkaTopicClient = kafkaTopicClient;

    final CreateSourceProperties properties = statement.getProperties();

    if (properties.getKsqlTopic().isPresent()) {
      this.topicName = properties.getKsqlTopic().get().toUpperCase();
      this.registerTopicCommand = null;
    } else {
      this.topicName = this.sourceName;
      this.registerTopicCommand = registerTopicFirst(properties);
    }

    this.schema = getStreamTableSchema(statement.getElements());

    if (properties.getKeyField().isPresent()) {
      final String name = properties.getKeyField().get().toUpperCase();

      final String keyFieldName = StringUtil.cleanQuotes(name);
      final Field keyField = schema.findField(keyFieldName)
          .orElseThrow(() -> new KsqlException(
              "The KEY column set in the WITH clause does not exist in the schema: '"
                  + keyFieldName + "'"
          ));

      this.keyField = KeyField.of(keyFieldName, keyField);
    } else {
      this.keyField = KeyField.none();
    }

    final Optional<String> timestampName = properties.getTimestampName();
    final Optional<String> timestampFormat = properties.getTimestampFormat();
    this.timestampExtractionPolicy = TimestampExtractionPolicyFactory
        .create(schema, timestampName, timestampFormat);

    this.keySerdeFactory = extractKeySerde(properties);
    this.serdeOptions = buildSerdeOptions(schema, properties, ksqlConfig);
  }

  Set<SerdeOption> getSerdeOptions() {
    return serdeOptions;
  }

  private static KsqlSchema getStreamTableSchema(final List<TableElement> tableElements) {
    if (tableElements.isEmpty()) {
      throw new KsqlException("The statement does not define any columns.");
    }

    SchemaBuilder tableSchema = SchemaBuilder.struct();
    for (final TableElement tableElement : tableElements) {
      if (tableElement.getName().equalsIgnoreCase(SchemaUtil.ROWTIME_NAME)
          || tableElement.getName().equalsIgnoreCase(SchemaUtil.ROWKEY_NAME)) {
        throw new KsqlException(
            SchemaUtil.ROWTIME_NAME + "/" + SchemaUtil.ROWKEY_NAME + " are "
            + "reserved token for implicit column."
            + " You cannot use them as a column name.");

      }
      tableSchema = tableSchema.field(
          tableElement.getName(),
          LogicalSchemas.fromSqlTypeConverter().fromSqlType(tableElement.getType())
      );
    }

    return KsqlSchema.of(tableSchema.build());
  }

  static void checkMetaData(
      final MetaStore metaStore,
      final String sourceName,
      final String topicName
  ) {
    if (metaStore.getSource(sourceName) != null) {
      throw new KsqlException(String.format("Source already exists: %s", sourceName));
    }

    if (metaStore.getTopic(topicName) == null) {
      throw new KsqlException(
          String.format("The corresponding topic does not exist: %s", topicName));
    }
  }

  private RegisterTopicCommand registerTopicFirst(
      final CreateSourceProperties properties
  ) {
    final String kafkaTopicName = properties.getKafkaTopic();
    if (!kafkaTopicClient.isTopicExists(kafkaTopicName)) {
      throw new KsqlException("Kafka topic does not exist: " + kafkaTopicName);
    }

    return new RegisterTopicCommand(this.topicName, false, properties);
  }

  private static SerdeFactory<?> extractKeySerde(
      final CreateSourceProperties properties
  ) {
    final Optional<SerdeFactory<Windowed<String>>> windowType = properties.getWindowType();

    //noinspection OptionalIsPresent - <?> causes confusion here
    if (!windowType.isPresent()) {
      return (SerdeFactory) Serdes::String;
    }

    return windowType.get();
  }

  private static Set<SerdeOption> buildSerdeOptions(
      final KsqlSchema schema,
      final CreateSourceProperties properties,
      final KsqlConfig ksqlConfig
  ) {
    final Format valueFormat = properties.getValueFormat();

    final ImmutableSet.Builder<SerdeOption> options = ImmutableSet.builder();

    final boolean singleValueField = schema.getSchema().fields().size() == 1;

    if (properties.getWrapSingleValues().isPresent() && !singleValueField) {
      throw new KsqlException("'" + DdlConfig.WRAP_SINGLE_VALUE + "' "
          + "is only valid for single-field value schemas");
    }

    if (properties.getWrapSingleValues().isPresent() && !valueFormat.supportsUnwrapping()) {
      throw new KsqlException("'" + DdlConfig.WRAP_SINGLE_VALUE + "' can not be used with format '"
          + valueFormat + "' as it does not support wrapping");
    }

    if (singleValueField && !properties.getWrapSingleValues()
        .orElseGet(() -> ksqlConfig.getBoolean(KsqlConfig.KSQL_WRAP_SINGLE_VALUES))
    ) {
      options.add(SerdeOption.UNWRAP_SINGLE_VALUES);
    }

    return Collections.unmodifiableSet(options.build());
  }
}
