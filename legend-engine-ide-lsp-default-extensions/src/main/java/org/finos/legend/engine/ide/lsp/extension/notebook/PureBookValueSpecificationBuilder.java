/*
 * Copyright 2025 Goldman Sachs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.finos.legend.engine.ide.lsp.extension.notebook;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.tuple.Tuples;
import org.eclipse.collections.impl.utility.ListIterate;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.CompileContext;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.HelperRelationalBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.ProcessingContext;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.ValueSpecificationBuilder;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Column;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Database;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Schema;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.datatype.DataType;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.ValueSpecificationVisitor;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.application.AppliedFunction;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.ClassInstance;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.classInstance.relation.RelationStoreAccessor;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.pure.generated.Root_meta_protocols_pure_vX_X_X_metamodel_store_relational_DataType;
import org.finos.legend.pure.generated.core_pure_protocol_protocol;
import org.finos.legend.pure.generated.core_relational_duckdb_relational_sqlQueryToString_duckdbExtension;
import org.finos.legend.pure.generated.core_relational_relational_protocols_pure_vX_X_X_transfers_metamodel_relational;
import org.finos.legend.pure.generated.core_relational_relational_transform_fromPure_pureToRelational;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.RelationType;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.relation.Table;
import org.finos.legend.pure.m3.execution.ExecutionSupport;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class PureBookValueSpecificationBuilder extends ValueSpecificationBuilder implements ValueSpecificationVisitor<ValueSpecification>
{
    private final Database parsedTargetDuckDBDatabase;
    private final Connection connection;

    public PureBookValueSpecificationBuilder(CompileContext context, MutableList<String> openVariables, ProcessingContext processingContext, Database database, Connection connection)
    {
        super(context, openVariables, processingContext);
        this.parsedTargetDuckDBDatabase = database;
        this.connection = connection;
    }

    private DataType transformDatabaseDataType(org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.datatype.DataType dataType)
    {
        ExecutionSupport executionSupport = getContext().getExecutionSupport();
        Root_meta_protocols_pure_vX_X_X_metamodel_store_relational_DataType transformedDataType = core_relational_relational_protocols_pure_vX_X_X_transfers_metamodel_relational.Root_meta_protocols_pure_vX_X_X_transformation_fromPureGraph_store_relational_pureDataTypeToAlloyDataType_DataType_1__DataType_1_(dataType, executionSupport);
        String json = core_pure_protocol_protocol.Root_meta_alloy_metadataServer_alloyToJSON_Any_1__String_1_(transformedDataType, executionSupport);
        try
        {
            return ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().readValue(json, DataType.class);
        }
        catch (IOException e)
        {
            throw new UnsupportedOperationException(e);
        }
    }

    private String escapeIfReservedSchemaName(String schemaName)
    {
        return (schemaName.equals("default")) ? "\"default\"" : schemaName;
    }

    private String safeCreateSchema(String schemaName)
    {
        String safeSchemaName = escapeIfReservedSchemaName(schemaName);
        return "DROP SCHEMA IF EXISTS " + safeSchemaName + "; CREATE SCHEMA " + safeSchemaName + ";";
    }

    private String safeCreateTableWithColumns(String schemaName, String tableName, MutableList<Pair<String, org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.datatype.DataType>> columnNameDataTypePairs)
    {
        String columnNamesAndTypes = columnNameDataTypePairs.stream()
                .map(pair -> String.format("%s %s",
                        pair.getOne(),
                        core_relational_duckdb_relational_sqlQueryToString_duckdbExtension.Root_meta_relational_functions_sqlQueryToString_duckDB_dataTypeToSqlTextDuckDB_DataType_1__String_1_(pair.getTwo(), getContext().getExecutionSupport())))
                .collect(Collectors.joining(", ", "(", ")"));
        return "CREATE OR REPLACE TABLE " + escapeIfReservedSchemaName(schemaName) + "." + tableName + " " + columnNamesAndTypes + ";";
    }

    private String safeAlterTableWithColumn(String schemaName, String tableName, Pair<String, org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.datatype.DataType> columnNameDataTypePair)
    {
        String columnName = columnNameDataTypePair.getOne();
        String columnType = core_relational_duckdb_relational_sqlQueryToString_duckdbExtension.Root_meta_relational_functions_sqlQueryToString_duckDB_dataTypeToSqlTextDuckDB_DataType_1__String_1_(columnNameDataTypePair.getTwo(), getContext().getExecutionSupport());
        return "ALTER TABLE " + escapeIfReservedSchemaName(schemaName) + "." + tableName + " ADD COLUMN IF NOT EXISTS " + columnName + " " + columnType + ";";
    }

    private void processDatabase(org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.Database compiledTargetDuckDBDatabase, MutableList<Pair<String, org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.datatype.DataType>> columnNameDataTypePairs, String targetSchemaName, String targetTableName)
    {
        try (Statement statement = this.connection.createStatement())
        {
            Optional<Schema> optionalSchema = ListIterate.select(this.parsedTargetDuckDBDatabase.schemas, s -> s.name.equals(targetSchemaName)).getFirstOptional();
            if (optionalSchema.isEmpty())
            {
                Schema targetSchema = new Schema();
                targetSchema.name = targetSchemaName;
                org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Table targetTable = getTableIfModified(columnNameDataTypePairs, targetSchema, targetTableName).orElseThrow();
                targetSchema.tables = Lists.mutable.with(targetTable);
                this.parsedTargetDuckDBDatabase.schemas = Lists.mutable.with(targetSchema);
                compiledTargetDuckDBDatabase._schemasAdd(HelperRelationalBuilder.processDatabaseSchema(targetSchema, getContext(), compiledTargetDuckDBDatabase));
                statement.executeUpdate(safeCreateSchema(targetSchemaName));
                statement.executeUpdate(safeCreateTableWithColumns(targetSchemaName, targetTableName, columnNameDataTypePairs));
            }
            else
            {
                Schema targetSchema = optionalSchema.get();
                Optional<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Table> optionalTable = getTableIfModified(columnNameDataTypePairs, targetSchema, targetTableName);
                if (optionalTable.isPresent())
                {
                    org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Table targetTable = optionalTable.get();
                    targetSchema.tables.removeIf(t -> t.name.equals(targetTable.name));
                    targetSchema.tables = Lists.mutable.withAll(targetSchema.tables).with(targetTable);
                    org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.Schema compiledTargetSchema = compiledTargetDuckDBDatabase._schemas().select(s -> s._name().equals(targetSchemaName)).getOnly();
                    Table compiledTargetTable = compiledTargetSchema._tables().select(t -> t._name().equals(targetTableName)).getOnly();
                    compiledTargetSchema._tablesRemove(compiledTargetTable)._tablesAdd(HelperRelationalBuilder.processDatabaseTable(targetTable, getContext(), compiledTargetSchema));
                    for (Pair<String, org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.datatype.DataType> columnNameDataTypePair : columnNameDataTypePairs)
                    {
                        statement.executeUpdate(safeAlterTableWithColumn(targetSchemaName, targetTableName, columnNameDataTypePair));
                    }
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private Optional<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Table> getTableIfModified(MutableList<Pair<String, org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.datatype.DataType>> columnNameDataTypePairs, Schema targetSchema, String targetTableName)
    {
        Optional<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Table> optionalTable = ListIterate.select(targetSchema.tables, t -> t.name.equals(targetTableName)).getFirstOptional();
        org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Table targetTable = optionalTable.orElseGet(org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Table::new);
        List<Column> columnsToAdd = getColumnsToAdd(columnNameDataTypePairs, targetTable);
        if (columnsToAdd.isEmpty())
        {
            return Optional.empty();
        }
        targetTable.name = targetTableName;
        targetTable.columns = Lists.mutable.withAll(targetTable.columns).withAll(columnsToAdd);
        return Optional.of(targetTable);
    }

    private List<Column> getColumnsToAdd(MutableList<Pair<String, org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.datatype.DataType>> columnNameDataTypePairs, org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Table targetTable)
    {
        MutableSet<String> existingColumnNameSet = Sets.mutable.fromStream(targetTable.columns.stream().map(c -> c.name));
        return columnNameDataTypePairs.select(pair -> !existingColumnNameSet.contains(pair.getOne()))
                .collect(pair ->
                {
                    Column targetColumn = new Column();
                    targetColumn.name = pair.getOne();
                    targetColumn.nullable = true;
                    targetColumn.type = transformDatabaseDataType(pair.getTwo());
                    return targetColumn;
                });
    }

    @Override
    public ValueSpecification visit(AppliedFunction appliedFunction)
    {
        if (appliedFunction.function.equals("write"))
        {
            // Process second parameter of write()
            org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.ValueSpecification writeSecondParameter = appliedFunction.parameters.get(1);
            if (writeSecondParameter instanceof ClassInstance)
            {
                ClassInstance classInstance = (ClassInstance) writeSecondParameter;
                Object value = classInstance.value;
                if (value instanceof RelationStoreAccessor)
                {
                    RelationStoreAccessor relationStoreAccessor = (RelationStoreAccessor) value;
                    List<String> paths = relationStoreAccessor.path;
                    if (paths.size() >= 2)
                    {
                        String targetDatabasePath = paths.get(0);
                        if (targetDatabasePath.equals("local::DuckDuckDatabase"))
                        {
                            String targetSchemaName = (paths.size() == 3) ? paths.get(1) : "default";
                            String targetTableName = paths.get(paths.size() - 1);

                            // Process first parameter of write()
                            org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.ValueSpecification writeFirstParameter = appliedFunction.parameters.get(0);
                            ValueSpecification compiledParameter = writeFirstParameter.accept(this);
                            RelationType relationType = (RelationType) compiledParameter._genericType()._typeArguments().getFirst()._rawType();
                            MutableList<Pair<String, org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.datatype.DataType>> columnNameDataTypePairs = relationType._columns().collect(c ->
                            {
                                org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.Column column = (org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.Column) c;
                                Type type = column._classifierGenericType()._typeArguments().getLast()._rawType();
                                org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.datatype.DataType dataType = core_relational_relational_transform_fromPure_pureToRelational.Root_meta_relational_transform_fromPure_pureTypeToDataType_Type_1__DataType_$0_1$_(type, getContext().getExecutionSupport());
                                return Tuples.pair(column._name(), dataType);
                            }).toList();

                            // Process database (Add new compiled schema/table/columns if not present)
                            org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.Database compiledTargetDuckDBDatabase = (org.finos.legend.pure.m3.coreinstance.meta.relational.metamodel.Database) (getContext().pureModel.getStore(targetDatabasePath));
                            processDatabase(compiledTargetDuckDBDatabase, columnNameDataTypePairs, targetSchemaName, targetTableName);
                        }
                    }
                }
            }
        }

        return super.visit(appliedFunction);
    }
}
