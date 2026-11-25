// EXP-091 재현 스크립트(단일 파일). relay의 전송 루프(lease 선점, cursor 뒤 읽기, 전송, 전체 ack 대기, cursor 전진)와
// ReservationEventConfiguration의 producer 설정을 메모리 이벤트 로그 위에서 흉내 내고, broker를 docker pause로 멈췄다 푼 뒤
// 토픽을 읽어 "같은 키의 높은 버전이 낮은 버전보다 먼저 첫 전달됐는가"를 센다. DB와 앱은 쓰지 않는다.
//
// 실행(저장소 루트, Docker와 kafka-clients jar 필요. 라벨은 끝나고 지운다):
//   docker run -d --name atlasexp-order-kafka --label atlasexp=order-repro -p 19092:19092 \
//     -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
//     -e KAFKA_LISTENERS=PLAINTEXT://:19092,CONTROLLER://:9093 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:19092 \
//     -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
//     -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
//     -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false apache/kafka:3.9.1
//   CONDITIONS="R2:2:500:5:none,R2chunk:2:500:5:chunk,NEG:1:500:5:reverse" TRIALS=12 PHASES=3 \
//     java -cp kafka-clients-4.2.2.jar:slf4j-api-2.0.17.jar load-tests/kafka-outage/RelayOrderRepro.java
//   docker rm -f atlasexp-order-kafka
//
// 조건은 이름:replica 수:relay 묶음:max.in.flight:방식. 방식은 none(현재 relay 방식), chunk(묶음을 64KB 덩어리로 나눠 하나씩 끝내고
// 보냄), reverse(음성 대조군: 묶음을 거꾸로 보내 알려진 역행을 만든다). 시행마다 토픽과 producer가 따로이고, 같은 broker·같은
// pause를 공유한다. 출력은 조건·장애 단계별 시행 수, 위반 시행 수, 위반 이벤트 합, 중복 최소/최대, 누락 합이다.
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

public final class RelayOrderRepro {
    static final int PARTITIONS = 3;
    static final long SEND_TIMEOUT_MS = 10_000;
    static final long LEASE_MS = 30_000;
    static final long POLL_MS = 200;
    static final int CHUNK_BYTES = 64 * 1024;
    static final String BOOTSTRAP = System.getenv().getOrDefault("BOOTSTRAP", "localhost:19092");
    static final String KAFKA_CONTAINER = System.getenv().getOrDefault("KAFKA_CONTAINER", "atlasexp-order-kafka");
    static final int LOAD_SECONDS = Integer.parseInt(System.getenv().getOrDefault("LOAD_SECONDS", "50"));

    record Event(long seq, String workflow, int version) {}

    /** 한 시행: 메모리 이벤트 로그, cursor, lease, replica마다 producer. */
    static final class Trial {
        final String id, topic, mode;
        final int replicas, batch, maxInFlight, phaseIndex, phaseCount;
        final List<Event> log = new ArrayList<>();
        final List<KafkaProducer<String, String>> producers = new ArrayList<>();
        final Map<String, Integer> phaseOf = new HashMap<>();
        final AtomicBoolean running = new AtomicBoolean(true);
        long cursor;
        long leaseUntil;

        Trial(String id, String[] condition, int phaseIndex, int phaseCount) {
            this.id = id;
            this.topic = "exp091." + id;
            this.replicas = Integer.parseInt(condition[1]);
            this.batch = Integer.parseInt(condition[2]);
            this.maxInFlight = Integer.parseInt(condition[3]);
            this.mode = condition[4];
            this.phaseIndex = phaseIndex;
            this.phaseCount = phaseCount;
        }

        synchronized void append(String workflow, int version, int phase) {
            log.add(new Event(log.size() + 1, workflow, version));
            phaseOf.put(workflow, phase);
        }

        synchronized List<Event> claimAndRead() {
            if (System.nanoTime() < leaseUntil || cursor >= log.size()) return null;
            leaseUntil = System.nanoTime() + LEASE_MS * 1_000_000;
            return new ArrayList<>(log.subList((int) cursor, Math.min(log.size(), (int) cursor + batch)));
        }

        synchronized void advance(Event last) {
            cursor = last.seq();
            leaseUntil = 0;
        }

        synchronized void release() {
            leaseUntil = 0;
        }

        synchronized boolean drained() {
            return cursor == log.size();
        }
    }

    static KafkaProducer<String, String> producer(Trial trial, int replica) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) SEND_TIMEOUT_MS);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) SEND_TIMEOUT_MS / 2);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) SEND_TIMEOUT_MS);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, trial.maxInFlight);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        if (trial.mode.equals("chunk")) {
            // 덩어리 하나가 파티션마다 batch 하나에 담기게 batch를 키우고, 덩어리는 flush로 바로 내보낸다.
            config.put(ProducerConfig.BATCH_SIZE_CONFIG, CHUNK_BYTES * 2);
            config.put(ProducerConfig.LINGER_MS_CONFIG, 1000);
        }
        config.put(ProducerConfig.CLIENT_ID_CONFIG, trial.id + "-" + replica);
        return new KafkaProducer<>(config);
    }

    static String json(Event e) {
        return "{\"workflowId\":\"" + e.workflow() + "\",\"aggregateVersion\":" + e.version() + ",\"eventSeq\":" + e.seq() + "}";
    }

    /** 한 묶음을 보내고 모든 ack(또는 실패)를 기다린다. 실패하면 예외, 이미 낸 레코드는 취소되지 않는다. */
    static void publish(Trial trial, KafkaProducer<String, String> producer, List<Event> batch) throws Exception {
        List<Event> order = new ArrayList<>(batch);
        if (trial.mode.equals("reverse")) Collections.reverse(order);
        List<CompletableFuture<Object>> sends = new ArrayList<>();
        int bytes = 0;
        for (Event e : order) {
            String value = json(e);
            if (trial.mode.equals("chunk") && !sends.isEmpty() && bytes + value.length() + 128 > CHUNK_BYTES) {
                await(producer, sends);
                sends.clear();
                bytes = 0;
            }
            CompletableFuture<Object> future = new CompletableFuture<>();
            producer.send(new ProducerRecord<>(trial.topic, e.workflow(), value), (m, error) -> {
                if (error != null) future.completeExceptionally(error);
                else future.complete(m);
            });
            sends.add(future);
            bytes += value.length() + 128;
        }
        await(producer, sends);
    }

    static void await(KafkaProducer<String, String> producer, List<CompletableFuture<Object>> sends) throws Exception {
        producer.flush();
        CompletableFuture.allOf(sends.toArray(new CompletableFuture<?>[0])).get(SEND_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
    }

    static void relay(Trial trial, int replica) {
        try {
            Thread.sleep((trial.phaseIndex * POLL_MS / trial.phaseCount + replica * POLL_MS / 2) % POLL_MS);
            while (trial.running.get()) {
                Thread.sleep(POLL_MS);
                List<Event> batch;
                while (trial.running.get() && (batch = trial.claimAndRead()) != null) {
                    try {
                        publish(trial, trial.producers.get(replica), batch);
                    } catch (Exception failure) {
                        trial.release();
                        break;
                    }
                    trial.advance(batch.get(batch.size() - 1));
                    if (batch.size() < trial.batch) break;
                }
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    static void docker(String verb) throws Exception {
        new ProcessBuilder("docker", verb, KAFKA_CONTAINER).inheritIO().start().waitFor();
    }

    public static void main(String[] args) throws Exception {
        int trialsPer = Integer.parseInt(System.getenv().getOrDefault("TRIALS", "12"));
        int phases = Integer.parseInt(System.getenv().getOrDefault("PHASES", "3"));
        List<Trial> trials = new ArrayList<>();
        for (String text : System.getenv().getOrDefault("CONDITIONS", "R2:2:500:5:none").split(",")) {
            String[] condition = text.split(":");
            for (int i = 0; i < trialsPer; i++) trials.add(new Trial(condition[0] + "-t" + trials.size(), condition, i, trialsPer));
        }
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", BOOTSTRAP))) {
            List<NewTopic> topics = new ArrayList<>();
            for (Trial t : trials) topics.add(new NewTopic(t.topic, PARTITIONS, (short) 1));
            admin.createTopics(topics).all().get(60, TimeUnit.SECONDS);
        }
        for (Trial t : trials) {
            for (int r = 0; r < t.replicas; r++) {
                t.producers.add(producer(t, r));
                int replica = r;
                Thread thread = new Thread(() -> relay(t, replica));
                thread.setDaemon(true);
                thread.start();
            }
        }
        // 부하: 초당 20 workflow(v1), 1초 뒤 v2. 단계마다 부하 10초 뒤 broker를 20초 pause한다.
        Map<String, Map<Integer, List<Trial>>> byCondition = new TreeMap<>();
        for (int phase = 1; phase <= phases; phase++) {
            long begin = System.nanoTime();
            Thread fault = new Thread(() -> {
                try {
                    Thread.sleep(10_000);
                    docker("pause");
                    Thread.sleep(20_000);
                    docker("unpause");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            fault.start();
            Map<Integer, List<String>> due = new HashMap<>();
            for (long tick = 0; tick < LOAD_SECONDS * 20 + 20; tick++) {
                long wait = begin + tick * 50_000_000L - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                for (Trial t : trials) {
                    if (tick < LOAD_SECONDS * 20) {
                        String workflow = UUID.randomUUID().toString();
                        t.append(workflow, 1, phase);
                        due.computeIfAbsent((int) tick + 20, k -> new ArrayList<>()).add(t.id + "|" + workflow);
                    }
                }
                for (String entry : due.getOrDefault((int) tick, List.of())) {
                    String[] parts = entry.split("\\|");
                    trials.stream().filter(t -> t.id.equals(parts[0])).findFirst().orElseThrow().append(parts[1], 2, phase);
                }
            }
            fault.join();
            if (phase < phases) Thread.sleep(10_000);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline && !trials.stream().allMatch(Trial::drained)) Thread.sleep(500);
        trials.forEach(t -> t.running.set(false));
        System.out.println("condition | phase | trials | violating trials | violations | duplicates min/max | lost");
        Map<String, List<int[]>> rows = new TreeMap<>();
        for (Trial t : trials) {
            int[][] result = audit(t, phases);
            for (int phase = 1; phase <= phases; phase++) rows.computeIfAbsent(t.id.split("-t")[0] + " | " + phase, k -> new ArrayList<>()).add(result[phase]);
        }
        rows.forEach((key, list) -> System.out.println(key + " | " + list.size() + " | " + list.stream().filter(r -> r[0] > 0).count()
                + " | " + list.stream().mapToInt(r -> r[0]).sum() + " | " + list.stream().mapToInt(r -> r[1]).min().orElse(0) + "/"
                + list.stream().mapToInt(r -> r[1]).max().orElse(0) + " | " + list.stream().mapToInt(r -> r[2]).sum()));
        trials.forEach(t -> t.producers.forEach(p -> p.close(Duration.ofSeconds(2))));
        System.exit(0);
    }

    /** 토픽을 파티션·offset 순서로 읽어 단계마다 {첫 전달 순서 위반 수, 중복 수, 누락 수}를 센다. */
    static int[][] audit(Trial trial, int phases) {
        Map<String, List<Integer>> perKey = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int[] copies = new int[phases + 1];
        int[] duplicates = new int[phases + 1];
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP, ConsumerConfig.GROUP_ID_CONFIG, "audit-" + trial.id,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (int p = 0; p < PARTITIONS; p++) partitions.add(new TopicPartition(trial.topic, p));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            Map<TopicPartition, List<ConsumerRecord<String, String>>> byPartition = new HashMap<>();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < ends.get(p))) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    byPartition.computeIfAbsent(new TopicPartition(r.topic(), r.partition()), k -> new ArrayList<>()).add(r);
                }
            }
            for (List<ConsumerRecord<String, String>> records : byPartition.values()) {
                for (ConsumerRecord<String, String> r : records) {
                    int version = Integer.parseInt(r.value().replaceAll(".*\"aggregateVersion\":(\\d+).*", "$1"));
                    perKey.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(version);
                    int phase = trial.phaseOf.get(r.key());
                    copies[phase]++;
                    if (!seen.add(r.key() + ":" + version)) duplicates[phase]++;
                }
            }
        }
        int[][] result = new int[phases + 1][3];
        for (Map.Entry<String, List<Integer>> entry : perKey.entrySet()) {
            Set<Integer> first = new HashSet<>();
            int lastFirst = 0;
            for (int version : entry.getValue()) {
                if (first.add(version)) {
                    if (version <= lastFirst) result[trial.phaseOf.get(entry.getKey())][0]++;
                    lastFirst = Math.max(lastFirst, version);
                }
            }
        }
        for (int phase = 1; phase <= phases; phase++) result[phase][1] = duplicates[phase];
        Set<String> expected = new HashSet<>();
        for (Event e : trial.log) expected.add(e.workflow() + ":" + e.version());
        expected.removeAll(seen);
        for (String lost : expected) result[trial.phaseOf.get(lost.split(":")[0])][2]++;
        return result;
    }
}
