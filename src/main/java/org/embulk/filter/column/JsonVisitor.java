package org.embulk.filter.column;

import org.embulk.config.ConfigException;
import org.embulk.filter.column.ColumnFilterPlugin.ColumnConfig;
import org.embulk.filter.column.ColumnFilterPlugin.PluginTask;

import org.embulk.spi.Exec;
import org.embulk.spi.Schema;
import org.embulk.spi.SchemaConfigException;
import org.embulk.spi.type.BooleanType;
import org.embulk.spi.type.DoubleType;
import org.embulk.spi.type.JsonType;
import org.embulk.spi.type.LongType;
import org.embulk.spi.type.StringType;
import org.embulk.spi.type.TimestampType;
import org.embulk.spi.type.Type;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonVisitor
{
    static final Logger logger = Exec.getLogger(ColumnFilterPlugin.class);
    final PluginTask task;
    final Schema inputSchema;
    final Schema outputSchema;
    final HashSet<String> shouldVisitSet = new HashSet<>();
    final HashMap<String, LinkedHashMap<String, JsonColumn>> jsonColumns = new HashMap<>();
    final HashMap<String, LinkedHashMap<String, JsonColumn>> jsonAddColumns = new HashMap<>();
    final HashMap<String, HashSet<String>> jsonDropColumns = new HashMap<>();

    JsonVisitor(PluginTask task, Schema inputSchema, Schema outputSchema)
    {
        this.task         = task;
        this.inputSchema  = inputSchema;
        this.outputSchema = outputSchema;

        buildShouldVisitSet();
        buildJsonSchema();
    }

    static Value getDefault(PluginTask task, String name, Type type, ColumnConfig columnConfig)
    {
        Object defaultValue = ColumnVisitorImpl.getDefault(task, name, type, columnConfig);
        if (defaultValue == null) {
            return ValueFactory.newNil();
        }
        if (type instanceof BooleanType) {
            return ValueFactory.newBoolean((Boolean) defaultValue);
        }
        else if (type instanceof LongType) {
            return ValueFactory.newInteger((Long) defaultValue);
        }
        else if (type instanceof DoubleType) {
            return ValueFactory.newFloat((Double) defaultValue);
        }
        else if (type instanceof StringType) {
            return ValueFactory.newString((String) defaultValue.toString());
        }
        else if (type instanceof JsonType) {
            return (Value) defaultValue;
        }
        else if (type instanceof TimestampType) {
            throw new ConfigException("type: timestamp is not available in json path");
        }
        else {
            throw new ConfigException(String.format("type: '%s' is not supported", type));
        }
    }

    private void jsonColumnsPut(String path, JsonColumn value)
    {
        String parentPath = JsonColumn.parentPath(path);
        if (! jsonColumns.containsKey(parentPath)) {
            jsonColumns.put(parentPath, new LinkedHashMap<String, JsonColumn>());
        }
        jsonColumns.get(parentPath).put(path, value);
    }

    private void jsonAddColumnsPut(String path, JsonColumn value)
    {
        String parentPath = JsonColumn.parentPath(path);
        if (! jsonAddColumns.containsKey(parentPath)) {
            jsonAddColumns.put(parentPath, new LinkedHashMap<String, JsonColumn>());
        }
        jsonAddColumns.get(parentPath).put(path, value);
    }

    private void jsonDropColumnsPut(String path)
    {
        String parentPath = JsonColumn.parentPath(path);
        if (! jsonDropColumns.containsKey(parentPath)) {
            jsonDropColumns.put(parentPath, new HashSet<String>());
        }
        jsonDropColumns.get(parentPath).add(path);
    }

    // build jsonColumns, jsonAddColumns, and jsonDropColumns
    private void buildJsonSchema()
    {
        List<ColumnConfig> columns = task.getColumns();
        List<ColumnConfig> addColumns = task.getAddColumns();
        List<ColumnConfig> dropColumns = task.getDropColumns();

        int i = 0;
        if (dropColumns.size() > 0) {
            for (ColumnConfig dropColumn : dropColumns) {
                String name = dropColumn.getName();
                // skip NON json path notation to build output schema
                if (! name.startsWith("$.")) {
                    continue;
                }
                jsonDropColumnsPut(name);
            }
        }
        else if (columns.size() > 0) {
            for (ColumnConfig column : columns) {
                String name = column.getName();
                // skip NON json path notation to build output schema
                if (! name.startsWith("$.")) {
                    continue;
                }
                if (column.getSrc().isPresent()) {
                    String src = column.getSrc().get();
                    jsonColumnsPut(name, new JsonColumn(name, null, null, src));
                }
                else if (column.getType().isPresent() && column.getDefault().isPresent()) { // add column
                    Type type = column.getType().get();
                    Value defaultValue = getDefault(task, name, type, column);
                    jsonColumnsPut(name, new JsonColumn(name, type, defaultValue));
                }
                else {
                    Type type = column.getType().isPresent() ? column.getType().get() : null;
                    jsonColumnsPut(name, new JsonColumn(name, type));
                }
            }
        }

        // Add columns to last. If you want to add to head or middle, you can use `columns` option
        if (addColumns.size() > 0) {
            for (ColumnConfig column : addColumns) {
                String name = column.getName();
                // skip NON json path notation to build output schema
                if (! name.startsWith("$.")) {
                    continue;
                }
                if (column.getSrc().isPresent()) {
                    String src = column.getSrc().get();
                    jsonAddColumnsPut(name, new JsonColumn(name, null, null, src));
                }
                else if (column.getType().isPresent() && column.getDefault().isPresent()) { // add column
                    Type type = column.getType().get();
                    Value defaultValue = getDefault(task, name, type, column);
                    jsonAddColumnsPut(name, new JsonColumn(name, type, defaultValue));
                }
                else {
                    throw new SchemaConfigException(String.format("add_columns: Column '%s' does not have \"src\", or \"type\" and \"default\"", name));
                }
            }
        }
    }

    // json partial path => Boolean to avoid unnecessary type: json visit
    private void buildShouldVisitSet()
    {
        ArrayList<ColumnConfig> columnConfigs = new ArrayList<>(task.getColumns());
        columnConfigs.addAll(task.getAddColumns());
        columnConfigs.addAll(task.getDropColumns());

        for (ColumnConfig columnConfig : columnConfigs) {
            String name = columnConfig.getName();
            if (!name.startsWith("$.")) {
                continue;
            }
            String[] parts = name.split("\\.");
            StringBuilder partialPath = new StringBuilder("$");
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].contains("[")) {
                    String[] arrayParts = parts[i].split("\\[");
                    partialPath.append(".").append(arrayParts[0]);
                    this.shouldVisitSet.add(partialPath.toString());
                    for (int j = 1; j < arrayParts.length; j++) {
                        // Simply add [0] or [*] here
                        partialPath.append("[").append(arrayParts[j]);
                        this.shouldVisitSet.add(partialPath.toString());
                    }
                }
                else {
                    partialPath.append(".").append(parts[i]);
                    this.shouldVisitSet.add(partialPath.toString());
                }
            }
        }
    }

    boolean shouldVisit(String jsonPath)
    {
        return shouldVisitSet.contains(jsonPath);
    }

    String newArrayJsonPath(String rootPath, int i)
    {
        String newPath = new StringBuilder(rootPath).append("[").append(Integer.toString(i)).append("]").toString();
        if (! shouldVisit(newPath)) {
            newPath = new StringBuilder(rootPath).append("[*]").toString(); // try [*] too
        }
        return newPath;
    }

    String newMapJsonPath(String rootPath, Value elementPathValue)
    {
        String elementPath = elementPathValue.asStringValue().asString();
        String newPath = new StringBuilder(rootPath).append(".").append(elementPath).toString();
        return newPath;
    }

    Value visitArray(String rootPath, ArrayValue arrayValue)
    {
        int size = arrayValue.size();
        ArrayList<Value> newValue = new ArrayList<>(size);
        int j = 0;
        if (this.jsonDropColumns.containsKey(rootPath)) {
            HashSet<String> jsonDropColumns = this.jsonDropColumns.get(rootPath);
            for (int i = 0; i < size; i++) {
                String newPath = newArrayJsonPath(rootPath, i);
                if (! jsonDropColumns.contains(newPath)) {
                    Value v = arrayValue.get(i);
                    newValue.add(j++, visit(newPath, v));
                }
            }
        }
        else if (this.jsonColumns.containsKey(rootPath)) {
            for (JsonColumn jsonColumn : this.jsonColumns.get(rootPath).values()) {
                int src = jsonColumn.getSrcBaseIndex().intValue();
                Value v = (src < arrayValue.size() ? arrayValue.get(src) : null);
                if (v == null) {
                    v = jsonColumn.getDefaultValue();
                }
                String newPath = jsonColumn.getPath();
                Value visited = visit(newPath, v);
                // int i = jsonColumn.getBaseIndex().intValue();
                // index is shifted, so j++ is used.
                newValue.add(j++, visited == null ? ValueFactory.newNil() : visited);
            }
        }
        else {
            for (int i = 0; i < size; i++) {
                String newPath = newArrayJsonPath(rootPath, i);
                Value v = arrayValue.get(i);
                newValue.add(j++, visit(newPath, v));
            }
        }
        if (this.jsonAddColumns.containsKey(rootPath)) {
            for (JsonColumn jsonColumn : this.jsonAddColumns.get(rootPath).values()) {
                int src = jsonColumn.getSrcBaseIndex().intValue();
                Value v = (src < arrayValue.size() ? arrayValue.get(src) : null);
                if (v == null) {
                    v = jsonColumn.getDefaultValue();
                }
                String newPath = jsonColumn.getPath();
                Value visited = visit(newPath, v);
                // this ignores specified index, but appends to last now
                newValue.add(j++, visited == null ? ValueFactory.newNil() : visited);
            }
        }
        return ValueFactory.newArray(newValue.toArray(new Value[0]), true);
    }

    Value visitMap(String rootPath, MapValue mapValue)
    {
        int size = mapValue.size();
        int i = 0;
        ArrayList<Value> newValue = new ArrayList<>(size * 2);
        if (this.jsonDropColumns.containsKey(rootPath)) {
            HashSet<String> jsonDropColumns = this.jsonDropColumns.get(rootPath);
            for (Map.Entry<Value, Value> entry : mapValue.entrySet()) {
                Value k = entry.getKey();
                Value v = entry.getValue();
                String newPath = newMapJsonPath(rootPath, k);
                if (! jsonDropColumns.contains(newPath)) {
                    Value visited = visit(newPath, v);
                    newValue.add(i++, k);
                    newValue.add(i++, visited);
                }
            }
        }
        else if (this.jsonColumns.containsKey(rootPath)) {
            Map<Value, Value> map = mapValue.map();
            for (JsonColumn jsonColumn : this.jsonColumns.get(rootPath).values()) {
                Value src = jsonColumn.getSrcBaseNameValue();
                Value v = map.get(src);
                if (v == null) {
                    v = jsonColumn.getDefaultValue();
                }
                String newPath = jsonColumn.getPath();
                Value visited = visit(newPath, v);
                newValue.add(i++, jsonColumn.getBaseNameValue());
                newValue.add(i++, visited == null ? ValueFactory.newNil() : visited);
            }
        }
        else {
            for (Map.Entry<Value, Value> entry : mapValue.entrySet()) {
                Value k = entry.getKey();
                Value v = entry.getValue();
                String newPath = newMapJsonPath(rootPath, k);
                Value visited = visit(newPath, v);
                newValue.add(i++, k);
                newValue.add(i++, visited);
            }
        }
        if (this.jsonAddColumns.containsKey(rootPath)) {
            Map<Value, Value> map = mapValue.map();
            for (JsonColumn jsonColumn : this.jsonAddColumns.get(rootPath).values()) {
                Value src = jsonColumn.getSrcBaseNameValue();
                Value v = map.get(src);
                if (v == null) {
                    v = jsonColumn.getDefaultValue();
                }
                String newPath = jsonColumn.getPath();
                Value visited = visit(newPath, v);
                newValue.add(i++, jsonColumn.getBaseNameValue());
                newValue.add(i++, visited == null ? ValueFactory.newNil() : visited);
            }
        }
        return ValueFactory.newMap(newValue.toArray(new Value[0]), true);
    }

    public Value visit(String rootPath, Value value)
    {
        if (! shouldVisit(rootPath)) {
            return value;
        }
        if (value == null) {
            return null;
        }
        else if (value.isArrayValue()) {
            return visitArray(rootPath, value.asArrayValue());
        }
        else if (value.isMapValue()) {
            return visitMap(rootPath, value.asMapValue());
        }
        else {
            return value;
        }
    }
}
