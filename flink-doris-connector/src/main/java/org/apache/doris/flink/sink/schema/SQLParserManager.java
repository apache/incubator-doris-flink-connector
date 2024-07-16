// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.flink.sink.schema;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterExpression.ColumnDataType;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import org.apache.commons.collections.CollectionUtils;
import org.apache.doris.flink.catalog.doris.FieldSchema;
import org.apache.doris.flink.sink.writer.serializer.jsondebezium.JsonDebeziumChangeUtils;
import org.apache.doris.flink.tools.cdc.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Use {@link net.sf.jsqlparser.parser.CCJSqlParserUtil} to parse SQL statements. */
public class SQLParserManager implements Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(SQLParserManager.class);

    /**
     * Doris' schema change only supports ADD, DROP, and RENAME operations. This method is only used
     * to parse the above schema change operations.
     */
    public List<String> parserAlterDDLs(
            SourceConnector sourceConnector, String ddl, String dorisTable) {
        List<String> ddlList = new ArrayList<>();
        try {
            Statement statement = CCJSqlParserUtil.parse(ddl);
            if (statement instanceof Alter) {
                Alter alterStatement = (Alter) statement;
                List<AlterExpression> alterExpressions = alterStatement.getAlterExpressions();
                for (AlterExpression alterExpression : alterExpressions) {
                    AlterOperation operation = alterExpression.getOperation();
                    switch (operation) {
                        case DROP:
                            String dropColumnDDL =
                                    processDropColumnOperation(alterExpression, dorisTable);
                            ddlList.add(dropColumnDDL);
                            break;
                        case ADD:
                            String addColumnDDL =
                                    processAddColumnOperation(
                                            sourceConnector, alterExpression, dorisTable);
                            ddlList.add(addColumnDDL);
                            break;
                        case CHANGE:
                            String changeColumnDDL =
                                    processChangeColumnOperation(alterExpression, dorisTable);
                            ddlList.add(changeColumnDDL);
                            break;
                        case RENAME:
                            String renameColumnDDL =
                                    processRenameColumnOperation(alterExpression, dorisTable);
                            ddlList.add(renameColumnDDL);
                            break;
                        default:
                            LOG.warn(
                                    "Unsupported alter ddl operations, operation={}, ddl={}",
                                    operation.name(),
                                    ddl);
                    }
                }
            } else {
                LOG.warn("Unsupported ddl operations, ddl={}", ddl);
            }
        } catch (JSQLParserException e) {
            LOG.warn("Failed to parse DDL SQL, SQL={}", ddl, e);
        }
        return ddlList;
    }

    private String processDropColumnOperation(AlterExpression alterExpression, String dorisTable) {
        String dropColumnDDL =
                SchemaChangeHelper.buildDropColumnDDL(dorisTable, alterExpression.getColumnName());
        LOG.info("Parsed drop column DDL SQL is: {}", dropColumnDDL);
        return dropColumnDDL;
    }

    private String processAddColumnOperation(
            SourceConnector sourceConnector, AlterExpression alterExpression, String dorisTable) {
        List<ColumnDataType> colDataTypeList = alterExpression.getColDataTypeList();
        if (colDataTypeList.size() != 1) {
            LOG.warn(
                    "Unknown alter change expression. colDataTypeListSize={}, colDataTypeList={}",
                    colDataTypeList.size(),
                    colDataTypeList.stream()
                            .map(ColumnDataType::toString)
                            .collect(Collectors.joining(";")));
        }
        ColumnDataType columnDataType = colDataTypeList.get(0);
        String columnName = columnDataType.getColumnName();
        ColDataType colDataType = columnDataType.getColDataType();
        String datatype = colDataType.getDataType();
        Integer length = null;
        Integer scale = null;
        if (CollectionUtils.isNotEmpty(colDataType.getArgumentsStringList())) {
            List<String> argumentsStringList = colDataType.getArgumentsStringList();
            length = Integer.parseInt(argumentsStringList.get(0));
            if (argumentsStringList.size() == 2) {
                scale = Integer.parseInt(argumentsStringList.get(1));
            }
        }
        datatype =
                JsonDebeziumChangeUtils.buildDorisTypeName(
                        sourceConnector, datatype, length, scale);

        List<String> columnSpecs = columnDataType.getColumnSpecs();
        String defaultValue = extractDefaultValue(columnSpecs);
        String comment = extractComment(columnSpecs);
        FieldSchema fieldSchema = new FieldSchema(columnName, datatype, defaultValue, comment);
        String addColumnDDL = SchemaChangeHelper.buildAddColumnDDL(dorisTable, fieldSchema);
        LOG.info("Parsed add column DDL SQL is: {}", addColumnDDL);
        return addColumnDDL;
    }

    private String processChangeColumnOperation(
            AlterExpression alterExpression, String dorisTable) {
        String columnNewName = alterExpression.getColDataTypeList().get(0).getColumnName();
        String columnOldName = alterExpression.getColumnOldName();
        String renameColumnDDL =
                SchemaChangeHelper.buildRenameColumnDDL(dorisTable, columnOldName, columnNewName);
        LOG.warn(
                "Note: Only rename column names are supported in doris. "
                        + "Therefore, the change syntax used here only supports the use of rename."
                        + " Parsed change column DDL SQL is: {}",
                renameColumnDDL);
        return renameColumnDDL;
    }

    private String processRenameColumnOperation(
            AlterExpression alterExpression, String dorisTable) {
        String columnNewName = alterExpression.getColumnName();
        String columnOldName = alterExpression.getColumnOldName();
        String renameColumnDDL =
                SchemaChangeHelper.buildRenameColumnDDL(dorisTable, columnOldName, columnNewName);
        LOG.info("Parsed rename column DDL SQL is: {}", renameColumnDDL);
        return renameColumnDDL;
    }

    private String extractDefaultValue(List<String> columnSpecs) {
        String defaultValue = null;
        if (columnSpecs.contains("default")) {
            int defaultIndex = columnSpecs.indexOf("default");
            defaultValue = removeQuotes(columnSpecs.get(defaultIndex + 1));
        } else if (columnSpecs.contains("DEFAULT")) {
            int defaultIndex = columnSpecs.indexOf("DEFAULT");
            defaultValue = removeQuotes(columnSpecs.get(defaultIndex + 1));
        }
        return defaultValue;
    }

    private String extractComment(List<String> columnSpecs) {
        String comment = null;
        if (columnSpecs.contains("comment")) {
            int commentIndex = columnSpecs.indexOf("comment");
            comment = removeQuotes(columnSpecs.get(commentIndex + 1));
        }
        if (columnSpecs.contains("COMMENT")) {
            int commentIndex = columnSpecs.indexOf("COMMENT");
            comment = removeQuotes(columnSpecs.get(commentIndex + 1));
        }
        return comment;
    }

    private String removeQuotes(String content) {
        if (content.startsWith("'") && content.endsWith("'") && content.length() > 1) {
            return content.substring(1, content.length() - 1);
        } else if (content.startsWith("\"") && content.endsWith("\"") && content.length() > 1) {
            return content.substring(1, content.length() - 1);
        }
        return content;
    }
}
