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

package org.apache.flink.connector.file.table;

import org.apache.flink.api.common.io.OutputFormat;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.file.table.PartitionWriter.Context;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.table.utils.LegacyRowResource;
import org.apache.flink.types.Row;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test for {@link PartitionWriter}s. */
public class PartitionWriterTest {

    @Rule public final LegacyRowResource usesLegacyRows = LegacyRowResource.INSTANCE;

    @ClassRule public static final TemporaryFolder TEMP_FOLDER = new TemporaryFolder();

    private final Map<String, List<Row>> records = new LinkedHashMap<>();

    private final OutputFormatFactory<Row> factory =
            path ->
                    new OutputFormat<Row>() {
                        private static final long serialVersionUID = -5797045183913321175L;

                        @Override
                        public void configure(Configuration parameters) {}

                        @Override
                        public void open(int taskNumber, int numTasks) {
                            records.put(getKey(), new ArrayList<>());
                        }

                        private String getKey() {
                            Path parent = path.getParent();
                            return parent.getName().startsWith("task-")
                                    ? parent.getName()
                                    : parent.getParent().getName()
                                            + Path.SEPARATOR
                                            + parent.getName();
                        }

                        @Override
                        public void writeRecord(Row record) {
                            records.get(getKey()).add(record);
                        }

                        @Override
                        public void close() {}
                    };

    private final String basePath = TEMP_FOLDER.newFolder().getPath();

    private final Context<Row> context =
            new Context<>(null, path -> factory.createOutputFormat(path));

    private FileSystemFactory fsFactory = FileSystem::get;

    private Path tmpPath = new Path(basePath);

    private PartitionTempFileManager manager = new PartitionTempFileManager(fsFactory, tmpPath, 0);

    private PartitionComputer<Row> computer =
            new PartitionComputer<Row>() {

                @Override
                public LinkedHashMap<String, String> generatePartValues(Row in) {
                    LinkedHashMap<String, String> ret = new LinkedHashMap<>(1);
                    ret.put("p", in.getField(0).toString());
                    return ret;
                }

                @Override
                public Row projectColumnsToWrite(Row in) {
                    return in;
                }
            };

    public PartitionWriterTest() throws Exception {}

    @Test
    public void testEmptySingleDirectoryWriter() throws Exception {
        SingleDirectoryWriter<Row> writer =
                new SingleDirectoryWriter<>(context, manager, computer, new LinkedHashMap<>());
        writer.close();
        Assert.assertTrue(records.isEmpty());
    }

    @Test
    public void testSingleDirectoryWriter() throws Exception {
        SingleDirectoryWriter<Row> writer =
                new SingleDirectoryWriter<>(context, manager, computer, new LinkedHashMap<>());

        writer.write(Row.of("p1", 1));
        writer.write(Row.of("p1", 2));
        writer.write(Row.of("p2", 2));
        writer.close();
        Assert.assertEquals("{task-0=[p1,1, p1,2, p2,2]}", records.toString());

        manager = new PartitionTempFileManager(fsFactory, tmpPath, 1);
        writer = new SingleDirectoryWriter<>(context, manager, computer, new LinkedHashMap<>());
        writer.write(Row.of("p3", 3));
        writer.write(Row.of("p5", 5));
        writer.write(Row.of("p2", 2));
        writer.close();
        Assert.assertEquals(
                "{task-0=[p1,1, p1,2, p2,2], task-1=[p3,3, p5,5, p2,2]}", records.toString());
    }

    @Test
    public void testGroupedPartitionWriter() throws Exception {
        GroupedPartitionWriter<Row> writer =
                new GroupedPartitionWriter<>(context, manager, computer);

        writer.write(Row.of("p1", 1));
        writer.write(Row.of("p1", 2));
        writer.write(Row.of("p2", 2));
        writer.close();
        Assert.assertEquals("{task-0/p=p1=[p1,1, p1,2], task-0/p=p2=[p2,2]}", records.toString());

        manager = new PartitionTempFileManager(fsFactory, tmpPath, 1);
        writer = new GroupedPartitionWriter<>(context, manager, computer);
        writer.write(Row.of("p3", 3));
        writer.write(Row.of("p4", 5));
        writer.write(Row.of("p5", 2));
        writer.close();
        Assert.assertEquals(
                "{task-0/p=p1=[p1,1, p1,2], task-0/p=p2=[p2,2], task-1/p=p3=[p3,3], task-1/p=p4=[p4,5], task-1/p=p5=[p5,2]}",
                records.toString());
    }

    @Test
    public void testDynamicPartitionWriter() throws Exception {
        DynamicPartitionWriter<Row> writer =
                new DynamicPartitionWriter<>(context, manager, computer);

        writer.write(Row.of("p1", 1));
        writer.write(Row.of("p2", 2));
        writer.write(Row.of("p1", 2));
        writer.close();
        Assert.assertEquals("{task-0/p=p1=[p1,1, p1,2], task-0/p=p2=[p2,2]}", records.toString());

        manager = new PartitionTempFileManager(fsFactory, tmpPath, 1);
        writer = new DynamicPartitionWriter<>(context, manager, computer);
        writer.write(Row.of("p4", 5));
        writer.write(Row.of("p3", 3));
        writer.write(Row.of("p5", 2));
        writer.close();
        Assert.assertEquals(
                "{task-0/p=p1=[p1,1, p1,2], task-0/p=p2=[p2,2], task-1/p=p4=[p4,5], task-1/p=p3=[p3,3], task-1/p=p5=[p5,2]}",
                records.toString());
    }
}
