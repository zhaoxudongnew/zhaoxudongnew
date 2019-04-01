/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.operations;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.calcite.FlinkTypeFactory;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;

import java.util.Collections;
import java.util.List;

/**
 * Wrapper for valid logical plans generated by Planner.
 */
@Internal
public class PlannerTableOperation implements TableOperation {

	private final RelNode calciteTree;
	private final TableSchema tableSchema;

	public PlannerTableOperation(RelNode calciteTree) {
		this.calciteTree = calciteTree;

		RelDataType rowType = calciteTree.getRowType();
		String[] fieldNames = rowType.getFieldNames().toArray(new String[0]);
		TypeInformation[] fieldTypes = rowType.getFieldList()
			.stream()
			.map(field -> FlinkTypeFactory.toTypeInfo(field.getType())).toArray(TypeInformation[]::new);

		this.tableSchema = new TableSchema(fieldNames, fieldTypes);
	}

	public RelNode getCalciteTree() {
		return calciteTree;
	}

	@Override
	public TableSchema getTableSchema() {
		return tableSchema;
	}

	@Override
	public List<TableOperation> getChildren() {
		return Collections.emptyList();
	}

	@Override
	public <T> T accept(TableOperationVisitor<T> visitor) {
		return visitor.visitOther(this);
	}
}
