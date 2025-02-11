/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.physical.config;

import java.util.List;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.planner.physical.visitor.GlobalDictionaryFieldInfo;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.ScanFilter;
import com.dremio.service.namespace.file.proto.FileConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import io.protostuff.ByteString;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeName("split-gen-manifest-scan")
public class SplitGenManifestScanTableFunctionContext extends TableFunctionContext {

    private final ByteString partitionSpecMap;
    private final byte[] icebergAnyColExpression;

    public SplitGenManifestScanTableFunctionContext(@JsonProperty("partitionSpecMap") ByteString partitionSpecMap,
                                                    @JsonProperty("icebergAnyColExpression") byte[] icebergAnyColExpression,
                                                    @JsonProperty("formatSettings") FileConfig formatSettings,
                                                    @JsonProperty("schema") BatchSchema fullSchema,
                                                    @JsonProperty("tableschema") BatchSchema tableSchema,
                                                    @JsonProperty("referencedTables") List<List<String>> tablePath,
                                                    @JsonProperty("scanFilter") ScanFilter scanFilter,
                                                    @JsonProperty("pluginId") StoragePluginId pluginId,
                                                    @JsonProperty("internalTablePluginId") StoragePluginId internalTablePluginId,
                                                    @JsonProperty("columns") List<SchemaPath> columns,
                                                    @JsonProperty("partitionColumns") List<String> partitionColumns,
                                                    @JsonProperty("globalDictionaryEncodedColumns") List<GlobalDictionaryFieldInfo> globalDictionaryEncodedColumns,
                                                    @JsonProperty("extendedProperty") ByteString extendedProperty,
                                                    @JsonProperty("arrowCachingEnabled") boolean arrowCachingEnabled,
                                                    @JsonProperty("convertedIcebergDataset") boolean isConvertedIcebergDataset,
                                                    @JsonProperty("icebergMetadata") boolean isIcebergMetadata) {
        super(formatSettings, fullSchema, tableSchema, tablePath, scanFilter, pluginId, internalTablePluginId, columns, partitionColumns, globalDictionaryEncodedColumns, extendedProperty, arrowCachingEnabled, isConvertedIcebergDataset, isIcebergMetadata);
        this.partitionSpecMap = partitionSpecMap;
        this.icebergAnyColExpression = icebergAnyColExpression;
    }

    public ByteString getPartitionSpecMap() {
        return partitionSpecMap;
    }

    public byte[] getIcebergAnyColExpression() {
        return icebergAnyColExpression;
    }
}
