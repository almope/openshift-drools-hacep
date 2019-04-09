/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
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
package org.kie.u212.core.infra.consumer;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.kie.u212.Config;
import org.kie.u212.core.infra.OffsetManager;
import org.kie.u212.core.infra.election.Callback;
import org.kie.u212.core.infra.election.State;
import org.kie.u212.core.infra.utils.ConsumerUtils;
import org.kie.u212.model.EventWrapper;
import org.kie.u212.model.StockTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultConsumer<T> implements EventConsumer, Callback {

    private Logger logger = LoggerFactory.getLogger(DefaultConsumer.class);
    private Map<TopicPartition, OffsetAndMetadata> offsetsEvents = new HashMap<>();
    private org.apache.kafka.clients.consumer.Consumer<String, T> kafkaConsumer, kafkaSecondaryConsumer;
    private ConsumerHandler consumerHandle;
    private String id;
    private Restarter externalContainer;
    private volatile State currentState;
    private volatile boolean leader = false;
    private volatile boolean started = false;
    private volatile String processingKey = "";
    private volatile long processingKeyOffset;
    private volatile boolean processingMaster = false;
    private volatile boolean processingSlave = false;
    private volatile boolean pollingEvents, pollingControl = true;
    private Properties configuration;
    private boolean demo = true;

    public DefaultConsumer(String id,
                           Properties properties,
                           Restarter externalContainer) {
        this.id = id;
        this.configuration = properties;
        this.externalContainer = externalContainer;
    }

    public void createConsumer(ConsumerHandler consumerHandler,
                               Properties properties) {
        this.consumerHandle = consumerHandler;
        kafkaConsumer = new KafkaConsumer<>(properties);
        if (!leader) {
            kafkaSecondaryConsumer = new KafkaConsumer<>(properties);
        }
    }

    public void restartConsumer(){
        logger.info("Restart Consumers");
        kafkaConsumer = new KafkaConsumer<>(configuration);
        if (!leader) {
            kafkaSecondaryConsumer = new KafkaConsumer<>(configuration);
        }else {
            kafkaSecondaryConsumer = null;
        }
    }

    public void stop() {
        kafkaConsumer.close();
        if (kafkaSecondaryConsumer != null) {
            kafkaSecondaryConsumer.close();
        }
        started = false;
    }

    public void updateStatus(State state) {
        if (started) {
            updateOnRunningConsumer(state);
        } else {
            enableConsumeAndStartLoop(state);
        }
        currentState = state;
    }

    @Override
    public void assign(List partitions) {
        if (leader) {
            assignAsALeader(partitions);
        } else {
            assignNotLeader(partitions);
        }
    }

    private void assignAsALeader(List partitions) {
        assignConsumer(kafkaConsumer, Config.EVENTS_TOPIC, partitions);
    }

    private void assignNotLeader(List partitions) {
        assignConsumer(kafkaConsumer, Config.EVENTS_TOPIC, partitions);
        assignConsumer(kafkaSecondaryConsumer, Config.CONTROL_TOPIC, partitions);
    }

    private void assignConsumer(Consumer<String, T> kafkaConsumer, String topic, List partitions) {
        List<PartitionInfo> partitionsInfo = kafkaConsumer.partitionsFor(topic);
        Collection<TopicPartition> partitionCollection = new ArrayList<>();

        if (partitionsInfo != null) {
            for (PartitionInfo partition : partitionsInfo) {
                if (partitions == null || partitions.contains(partition.partition())) {
                    partitionCollection.add(new TopicPartition(partition.topic(), partition.partition()));
                }
            }

            if (!partitionCollection.isEmpty()) {
                kafkaConsumer.assign(partitionCollection);
            }
        }
        kafkaConsumer.assignment().forEach(topicPartition -> kafkaConsumer.seekToBeginning(partitionCollection));
    }

    @Override
    public void poll(int size,
                     long duration,
                     boolean commitSync) {
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Starting exit...\n");
            kafkaConsumer.wakeup();
            if (kafkaSecondaryConsumer != null) {
                kafkaSecondaryConsumer.wakeup();
            }
            try {
                mainThread.join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(),
                             e);
            }
        }));

        if (kafkaConsumer == null) {
            throw new IllegalStateException("Can't poll, kafkaConsumer not subscribed or null!");
        }

        if (kafkaSecondaryConsumer == null) {
            throw new IllegalStateException("Can't poll, kafkaConsumer not subscribed or null!");
        }

        try {
            if (duration == -1) {
                while (true) {
                    consume(size);
                }
            } else {
                long startTime = System.currentTimeMillis();
                while (false || (System.currentTimeMillis() - startTime) < duration) {
                    consume(size);
                }
            }
        } catch (WakeupException e) {
        } finally {
            try {
                kafkaConsumer.commitSync();
                if (logger.isDebugEnabled()) {
                    for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsetsEvents.entrySet()) {
                        logger.debug("Consumer %s - partition %s - lastOffset %s\n", this.id, entry.getKey().partition(), entry.getValue().offset());
                    }
                }
                OffsetManager.store(offsetsEvents);
            } finally {
                logger.info("Closing kafkaConsumer on the loop");
                kafkaConsumer.close();
                kafkaSecondaryConsumer.close();
            }
        }
    }

    private void updateOnRunningConsumer(State state) {
        if (state.equals(State.LEADER) && !leader) {
            stopConsume();
            restartConsumer();
            enableConsumeAndStartLoop(state);
            logger.info("updateOnRunningConsumer leader:{}", leader);
        } else if (state.equals(State.NOT_LEADER) && leader) {
            stopConsume();
            enableConsumeAndStartLoop(state);
            logger.info("updateOnRunningConsumer leader:{}", leader);
        }
    }

    private void enableConsumeAndStartLoop(State state) {
        if(demo) {
            logger.info("enableConsumeAndStartLoop");
        }
        if (state.equals(State.LEADER) && !leader) {
            leader = true;
            processingMaster = false;
        } else if (state.equals(State.NOT_LEADER) && leader) {
            leader = false;
            processingSlave = true;
            pollingEvents = true;
            pollingControl = false;
        } else if (state.equals(State.NOT_LEADER) && !leader) {
            leader = false;
            processingSlave = true;
            pollingEvents = false;
            pollingControl = true;
        } else if (state.equals(State.BECOMING_LEADER) && !leader) {
            leader = true;
            processingMaster = false;
        }

        setLastProcessedKey();
        startConsume();
    }

    private void setLastProcessedKey() {
        EventWrapper<StockTickEvent> lastWrapper = ConsumerUtils.getLastEvent(Config.CONTROL_TOPIC, configuration);
        settingsOnAEmptyControlTopic(lastWrapper);
        processingKey = lastWrapper.getKey();
        processingKeyOffset = lastWrapper.getOffset();
        if(demo){ logger.info("On Startup, last processedEvent on Control topic is key:{}  Offset:{}", lastWrapper.getKey(), lastWrapper.getOffset()); }
    }

    void settingsOnAEmptyControlTopic(EventWrapper<StockTickEvent> lastWrapper) {
        if (lastWrapper.getKey() == null && lastWrapper.getOffset() == 0l) {
            if(demo){logger.info("Empty topic control");}
            if (leader) {
                processingMaster = true;// the leader starts to process from events topic and publish on control topic
            } else {
                processingSlave = false;
                pollingEvents = false;
                pollingControl = true; // the nodes start to poll only the controlTopic until a event is available
            }
        }
    }

    private void startConsume() {
        assign(null);
        started = true;
    }

    private void stopConsume(){
        started = false;
    }

    private void consume(int size) {
        if (started) {
            if (leader) {
                defaultProcessAsLeader(size);
            } else {
                defaultProcessAsNotLeader(size);
            }
        }
    }

    private void defaultProcessAsLeader(int size) {
        pollingEvents = true;
        ConsumerRecords<String, T> records = kafkaConsumer.poll(Duration.of(size, ChronoUnit.MILLIS));
        for (ConsumerRecord<String, T> record : records) {
            processLeader(record);
        }
    }

    private void processLeader(ConsumerRecord<String, T> record) {
        ConsumerUtils.prettyPrinter(id, "groupId", record, processingMaster);
        if (record.key().equals(processingKey)) {
            if(demo) { logger.info("Reached last processed key, starting processingMaster new events"); }
            processingMaster = true;
        } else if (processingMaster) {
            consumerHandle.process(record, currentState, this);
            processingKey = record.key();// the new processed became the new processingKey
            if(demo) { logger.info("new value:processingKey:{}", processingKey); }
            saveOffset(record,kafkaConsumer);
        }
    }

    private void defaultProcessAsNotLeader(int size) {

        if (pollingEvents) {
            ConsumerRecords<String, T> records = kafkaConsumer.poll(Duration.of(size, ChronoUnit.MILLIS));
            for (ConsumerRecord<String, T> record : records) {
                processEventsAsANonLeader(record);
                if (!pollingEvents) {
                    break;
                }
            }
        }


        if (pollingControl) {
            ConsumerRecords<String, T> records = kafkaSecondaryConsumer.poll(Duration.of(size, ChronoUnit.MILLIS));
            for (ConsumerRecord<String, T> record : records) {
                processControlAsANonLeader(record);
                if (!pollingControl) {
                    break;
                }
            }
        }
    }


    private void processEventsAsANonLeader(ConsumerRecord<String, T> record) {
        if (demo) {
            logger.info("process event processingKey:{}", processingKey);
        }
        ConsumerUtils.prettyPrinter(id, "groupId", record, processingSlave);
        if (record.key().equals(processingKey)) {
            if(demo) {
                logger.info("Reached last processed key, stopping the processingMaster on events' topic, move on the control Topic");
            }
            pollingEvents = false;
            pollingControl = true;
            processingSlave = false;
        } else if (processingSlave) {
            if(demo){
                logger.info("processing slave events");
            }
            consumerHandle.process(record, currentState, this);
            saveOffset(record, kafkaConsumer);
        }
    }

    private void processControlAsANonLeader(ConsumerRecord<String, T> record) {
        if(demo) { logger.info("process control processingSlave:{}", processingSlave); }

        ConsumerUtils.prettyPrinter(id, "groupId", record, false);
        if (record.offset() == processingKeyOffset + 1 || record.offset() == 0) {
            if(record.offset() > 0) {
                processingKey = record.key();
                processingKeyOffset = record.offset();
            }

            if(demo) { logger.info("New key found on control setting startProcessing key:{} new Offset:{} going to poll events to find this new key", processingKey, processingKeyOffset); }

            pollingControl = false;
            pollingEvents = true;
            processingSlave = true;
        }

        saveOffset(record, kafkaSecondaryConsumer);

        if(demo) { logger.info("nothing to do on control : record key:{} processingSlave:{} ", record.key(), processingSlave);
        logger.info("exiting from Process Control pollingControl:{} pollingEvents:{}",
                        pollingControl,
                        pollingEvents);
        }
    }

    private void saveOffset(ConsumerRecord<String, T> record, Consumer<String, T> kafkaSecondaryConsumer) {
        Map map = new HashMap();
        map.put(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
        kafkaSecondaryConsumer.commitSync(map);
    }
}