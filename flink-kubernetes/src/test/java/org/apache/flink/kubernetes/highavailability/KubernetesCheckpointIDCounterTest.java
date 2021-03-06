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

package org.apache.flink.kubernetes.highavailability;

import org.apache.flink.api.common.JobStatus;
import org.apache.flink.core.testutils.FlinkMatchers;
import org.apache.flink.kubernetes.kubeclient.resources.KubernetesLeaderElector;
import org.apache.flink.kubernetes.utils.Constants;
import org.apache.flink.kubernetes.utils.KubernetesUtils;

import org.junit.Test;

import java.util.concurrent.CompletionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

/** Tests for {@link KubernetesCheckpointIDCounter} operations. */
public class KubernetesCheckpointIDCounterTest extends KubernetesHighAvailabilityTestBase {

    @Test
    public void testGetAndIncrement() throws Exception {
        new Context() {
            {
                runTest(
                        () -> {
                            leaderCallbackGrantLeadership();

                            final KubernetesCheckpointIDCounter checkpointIDCounter =
                                    new KubernetesCheckpointIDCounter(
                                            flinkKubeClient, LEADER_CONFIGMAP_NAME, LOCK_IDENTITY);
                            checkpointIDCounter.setCount(100L);
                            final long counter = checkpointIDCounter.getAndIncrement();
                            assertThat(counter, is(100L));
                            assertThat(checkpointIDCounter.get(), is(101L));
                        });
            }
        };
    }

    @Test
    public void testShutdown() throws Exception {
        new Context() {
            {
                runTest(
                        () -> {
                            leaderCallbackGrantLeadership();

                            final KubernetesCheckpointIDCounter checkpointIDCounter =
                                    new KubernetesCheckpointIDCounter(
                                            flinkKubeClient, LEADER_CONFIGMAP_NAME, LOCK_IDENTITY);
                            checkpointIDCounter.start();

                            checkpointIDCounter.setCount(100L);

                            assertThat(
                                    getLeaderConfigMap()
                                            .getData()
                                            .get(Constants.CHECKPOINT_COUNTER_KEY),
                                    is("100"));

                            checkpointIDCounter.shutdown(JobStatus.FINISHED).join();

                            assertThat(
                                    getLeaderConfigMap()
                                            .getData()
                                            .containsKey(Constants.CHECKPOINT_COUNTER_KEY),
                                    is(false));
                        });
            }
        };
    }

    @Test
    public void testShutdownForLocallyTerminatedJobStatus() throws Exception {
        new Context() {
            {
                runTest(
                        () -> {
                            leaderCallbackGrantLeadership();

                            final KubernetesCheckpointIDCounter checkpointIDCounter =
                                    new KubernetesCheckpointIDCounter(
                                            flinkKubeClient, LEADER_CONFIGMAP_NAME, LOCK_IDENTITY);
                            checkpointIDCounter.start();

                            checkpointIDCounter.setCount(100L);

                            assertThat(
                                    getLeaderConfigMap()
                                            .getData()
                                            .get(Constants.CHECKPOINT_COUNTER_KEY),
                                    is("100"));

                            checkpointIDCounter.shutdown(JobStatus.SUSPENDED).join();

                            assertThat(
                                    getLeaderConfigMap()
                                            .getData()
                                            .containsKey(Constants.CHECKPOINT_COUNTER_KEY),
                                    is(true));
                        });
            }
        };
    }

    @Test
    public void testIdempotentShutdown() throws Exception {
        new Context() {
            {
                runTest(
                        () -> {
                            leaderCallbackGrantLeadership();

                            final KubernetesCheckpointIDCounter checkpointIDCounter =
                                    new KubernetesCheckpointIDCounter(
                                            flinkKubeClient, LEADER_CONFIGMAP_NAME, LOCK_IDENTITY);
                            checkpointIDCounter.start();

                            assertThat(
                                    getLeaderConfigMap()
                                            .getData()
                                            .containsKey(Constants.CHECKPOINT_COUNTER_KEY),
                                    is(false));

                            checkpointIDCounter.shutdown(JobStatus.FINISHED).join();

                            assertThat(
                                    getLeaderConfigMap()
                                            .getData()
                                            .containsKey(Constants.CHECKPOINT_COUNTER_KEY),
                                    is(false));

                            // a second shutdown should work without causing any errors
                            checkpointIDCounter.shutdown(JobStatus.FINISHED).join();
                        });
            }
        };
    }

    @Test
    public void testShutdownFailureDueToMissingConfigMap() throws Exception {
        new Context() {
            {
                runTest(
                        () -> {
                            leaderCallbackGrantLeadership();

                            final KubernetesCheckpointIDCounter checkpointIDCounter =
                                    new KubernetesCheckpointIDCounter(
                                            flinkKubeClient, LEADER_CONFIGMAP_NAME, LOCK_IDENTITY);
                            checkpointIDCounter.start();

                            // deleting the ConfigMap from outside of the CheckpointIDCounter while
                            // still using the counter (which is stored as an entry in the
                            // ConfigMap) causes an unexpected failure which we want to simulate
                            // here
                            flinkKubeClient.deleteConfigMap(LEADER_CONFIGMAP_NAME);

                            assertThrows(
                                    CompletionException.class,
                                    () -> checkpointIDCounter.shutdown(JobStatus.FINISHED).get());

                            // fixing the internal issue should make the shutdown succeed again
                            KubernetesUtils.createConfigMapIfItDoesNotExist(
                                    flinkKubeClient, LEADER_CONFIGMAP_NAME, getClusterId());
                            checkpointIDCounter.shutdown(JobStatus.FINISHED).get();
                        });
            }
        };
    }

    @Test
    public void testGetAndIncrementWithNoLeadership() throws Exception {
        new Context() {
            {
                runTest(
                        () -> {
                            leaderCallbackGrantLeadership();

                            final KubernetesCheckpointIDCounter checkpointIDCounter =
                                    new KubernetesCheckpointIDCounter(
                                            flinkKubeClient, LEADER_CONFIGMAP_NAME, LOCK_IDENTITY);
                            checkpointIDCounter.setCount(100L);

                            // lost leadership
                            getLeaderCallback().notLeader();
                            electionEventHandler.waitForRevokeLeader(TIMEOUT);
                            getLeaderConfigMap()
                                    .getAnnotations()
                                    .remove(KubernetesLeaderElector.LEADER_ANNOTATION_KEY);

                            try {
                                checkpointIDCounter.getAndIncrement();
                                fail(
                                        "We should get an exception when trying to GetAndIncrement no leadership.");
                            } catch (Exception ex) {
                                final String errMsg =
                                        "Failed to update ConfigMap "
                                                + LEADER_CONFIGMAP_NAME
                                                + " since "
                                                + "current KubernetesCheckpointIDCounter does not have the leadership.";
                                assertThat(ex, FlinkMatchers.containsMessage(errMsg));
                            }
                        });
            }
        };
    }

    @Test
    public void testSetAndGet() throws Exception {
        new Context() {
            {
                runTest(
                        () -> {
                            leaderCallbackGrantLeadership();

                            final KubernetesCheckpointIDCounter checkpointIDCounter =
                                    new KubernetesCheckpointIDCounter(
                                            flinkKubeClient, LEADER_CONFIGMAP_NAME, LOCK_IDENTITY);
                            checkpointIDCounter.setCount(100L);
                            final long counter = checkpointIDCounter.get();
                            assertThat(counter, is(100L));
                        });
            }
        };
    }

    @Test
    public void testGetWhenConfigMapNotExist() throws Exception {
        new Context() {
            {
                runTest(
                        () -> {
                            final KubernetesCheckpointIDCounter checkpointIDCounter =
                                    new KubernetesCheckpointIDCounter(
                                            flinkKubeClient, LEADER_CONFIGMAP_NAME, LOCK_IDENTITY);
                            try {
                                checkpointIDCounter.get();
                                fail(
                                        "We should get an exception when trying to get checkpoint id counter but "
                                                + "ConfigMap does not exist.");
                            } catch (Exception ex) {
                                final String errMsg =
                                        "ConfigMap " + LEADER_CONFIGMAP_NAME + " does not exist.";
                                assertThat(ex, FlinkMatchers.containsMessage(errMsg));
                            }
                        });
            }
        };
    }

    @Test
    public void testSetWithNoLeadershipShouldNotBeIssued() throws Exception {
        new Context() {
            {
                runTest(
                        () -> {
                            leaderCallbackGrantLeadership();

                            final KubernetesCheckpointIDCounter checkpointIDCounter =
                                    new KubernetesCheckpointIDCounter(
                                            flinkKubeClient, LEADER_CONFIGMAP_NAME, LOCK_IDENTITY);

                            checkpointIDCounter.setCount(2L);

                            // lost leadership
                            getLeaderCallback().notLeader();
                            electionEventHandler.waitForRevokeLeader(TIMEOUT);
                            getLeaderConfigMap()
                                    .getAnnotations()
                                    .remove(KubernetesLeaderElector.LEADER_ANNOTATION_KEY);

                            checkpointIDCounter.setCount(100L);
                            assertThat(checkpointIDCounter.get(), is(2L));
                        });
            }
        };
    }
}
