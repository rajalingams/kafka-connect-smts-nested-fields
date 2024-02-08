package io.confluent.connect.custom.transforms;

import io.confluent.connect.custom.utils.FieldListJsonPathExtractor;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.kafka.connect.transforms.util.Requirements.requireMap;
import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

public class NestedValueToKeysAndHeader<R extends ConnectRecord<R>> extends BaseNestedValue<R> {
    Schema keySchema =  SchemaBuilder
            .map(Schema.STRING_SCHEMA, SchemaBuilder.string().optional().build())
            .doc("Schema generated by NestedValueToKeysAndHeader SMT")
            .name("MAP_STRING_TO_ANY")
            .build();
    public interface ConfigName {
        String KEY_FIELD_MAPPING = "keyFieldMapping";
        String HEADER_FIELD_MAPPING = "headerFieldMapping";
    }

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(NestedValueToKeysAndHeader.ConfigName.KEY_FIELD_MAPPING, ConfigDef.Type.LIST, null, ConfigDef.Importance.LOW,
                    "Map of key field name to json path in the message body. eg: field1:jsonpath1,field2:jsonpath2..")
            .define(ConfigName.HEADER_FIELD_MAPPING, ConfigDef.Type.LIST, null, ConfigDef.Importance.LOW,
                    "Map of header field name to json path in the message body. eg: field1:jsonpath1,field2:jsonpath2..");

    private static final String PURPOSE = "construct the record key and header from value";

    private List<String> keyFieldList;
    private Map<String, String> keyFieldMap;
    private List<String> headerFieldList;
    private Map<String, String> headerFieldMap;
    private FieldListJsonPathExtractor keyFieldExtractor = null;
    private FieldListJsonPathExtractor headerFieldExtractor = null;

    @Override
    public void configure(Map<String, ?> props) {
        final SimpleConfig config = new SimpleConfig(CONFIG_DEF, props);
        headerFieldList = config.getList(ConfigName.HEADER_FIELD_MAPPING);
        keyFieldList = config.getList(ConfigName.KEY_FIELD_MAPPING);
        if (this.keyFieldList == null || this.keyFieldList.isEmpty()) {
            throw new ConfigException(
                    "`" + NestedValueToKeys.ConfigName.KEY_FIELD_MAPPING + "` is required for `" + getClass().getName() + "`"
            );
        }
        keyFieldMap = parseMappings(this.keyFieldList, NestedValueToKeys.ConfigName.KEY_FIELD_MAPPING);
        keyFieldExtractor = new FieldListJsonPathExtractor(keyFieldMap, NestedValueToKeys.ConfigName.KEY_FIELD_MAPPING);
        if (this.headerFieldList == null || this.headerFieldList.isEmpty()) {
            throw new ConfigException(
                    "`" + NestedValueToHeader.ConfigName.HEADER_FIELD_MAPPING + "` is required for `" + getClass().getName() + "`"
            );
        }
        headerFieldMap = parseMappings(this.headerFieldList, NestedValueToHeader.ConfigName.HEADER_FIELD_MAPPING);
        headerFieldExtractor = new FieldListJsonPathExtractor(headerFieldMap, NestedValueToHeader.ConfigName.HEADER_FIELD_MAPPING);
    }

    @Override
    protected R applySchemaless(R record) {
        final Map<String, Object> value = requireMap(record.value(), PURPOSE);
        Headers headers = record.headers().duplicate();
        Map<String, Object> keyData = new LinkedHashMap<>();
        for (Map.Entry<String, String> fieldItem : keyFieldMap.entrySet()) {
            keyData.put(fieldItem.getKey(), value.get(fieldItem.getValue()));
        }
        if (headerFieldMap != null) {
            for (Map.Entry<String, String> fieldItem : headerFieldMap.entrySet()) {
                headers.add(fieldItem.getKey(), value.get(fieldItem.getValue()), null);
            }
        }
        return record.newRecord(record.topic(), null, null, keyData, record.valueSchema(), record.value(), record.timestamp(), headers);
    }

    @Override
    protected R applyWithSchema(R record) {
        final Struct value = requireStruct(record.value(), PURPOSE);
        Object messageBody = extractObject(record);
        Headers headers = record.headers().duplicate();
        for (Map.Entry<String, String> fieldItem : headerFieldMap.entrySet()) {
            headers.add(fieldItem.getKey(), headerFieldExtractor.extractValue(fieldItem.getKey(), messageBody), null);
        }
        return record.newRecord(record.topic(), null, keySchema,
                keyFieldExtractor.extractValues(messageBody), value.schema(), value, record.timestamp(), headers);
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

}
