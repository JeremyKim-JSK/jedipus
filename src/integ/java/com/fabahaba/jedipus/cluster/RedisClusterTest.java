package com.fabahaba.jedipus.cluster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fabahaba.jedipus.FutureLongReply;
import com.fabahaba.jedipus.FutureReply;
import com.fabahaba.jedipus.HostPort;
import com.fabahaba.jedipus.RESP;
import com.fabahaba.jedipus.RedisClient;
import com.fabahaba.jedipus.cluster.RedisClusterExecutor.ReadMode;
import com.fabahaba.jedipus.cmds.Cmd;
import com.fabahaba.jedipus.cmds.Cmds;
import com.fabahaba.jedipus.exceptions.AskNodeException;
import com.fabahaba.jedipus.exceptions.MaxRedirectsExceededException;
import com.fabahaba.jedipus.exceptions.RedisClusterDownException;
import com.fabahaba.jedipus.exceptions.RedisConnectionException;
import com.fabahaba.jedipus.exceptions.SlotMovedException;
import com.fabahaba.jedipus.exceptions.UnhandledAskNodeException;
import com.fabahaba.jedipus.pool.ClientPool;
import com.fabahaba.jedipus.primitive.RedisClientFactory;

public class RedisClusterTest extends BaseRedisClientTest {

  protected final Logger log = Logger.getLogger(getClass().getSimpleName());

  private static final int MAX_WAIT_CLUSTER_READY = 2000;

  private static final String ANNOUNCE_IP = Optional
      .ofNullable(System.getProperty("jedipus.redis.cluster.announceip")).orElse("127.0.0.1");

  private static final int STARTING_PORT =
      Optional.ofNullable(System.getProperty("jedipus.redis.cluster.startingport"))
          .map(Integer::parseInt).orElse(7379);

  private static final int NUM_MASTERS =
      Optional.ofNullable(System.getProperty("jedipus.redis.cluster.nummasters"))
          .map(Integer::parseInt).orElse(3);

  private static final int NUM_SLAVES_EACH =
      Optional.ofNullable(System.getProperty("jedipus.redis.cluster.numslaveseach"))
          .map(Integer::parseInt).orElse(1);

  private static final int NUM_SLAVES = NUM_MASTERS * NUM_SLAVES_EACH;
  private static final RedisClientFactory.Builder REDIS_CLIENT_BUILDER =
      RedisClientFactory.startBuilding();

  private static final Node[] masters = new Node[NUM_MASTERS];
  private static final Node[] slaves = new Node[NUM_SLAVES];
  private static final int MAX_SLOT_RANGE = (int) Math.ceil(CRC16.NUM_SLOTS / (double) NUM_MASTERS);
  private static final int[][] slots = new int[NUM_MASTERS][];

  private static Set<Node> discoveryNodes;
  private static final Queue<Node> pendingReset = new ArrayDeque<>(NUM_SLAVES);

  static final RedisClient[] masterClients = new RedisClient[NUM_MASTERS];

  @BeforeClass
  public static void beforeClass() {

    int port = STARTING_PORT;
    for (int i = 0, slotOffset = 0; i < NUM_MASTERS; i++, port++, slotOffset += MAX_SLOT_RANGE) {

      final Node master = Node.create(ANNOUNCE_IP, port);
      masters[i] = master;

      final RedisClient client = RedisClientFactory.startBuilding().create(master);
      masterClients[i] = client;

      final int endSlot = Math.min(slotOffset + MAX_SLOT_RANGE, CRC16.NUM_SLOTS);
      slots[i] = IntStream.range(slotOffset, endSlot).toArray();
    }

    discoveryNodes = Collections.singleton(masters[0]);

    for (int i = 0; i < NUM_SLAVES; i++, port++) {
      slaves[i] = Node.create(ANNOUNCE_IP, port);
    }
  }

  @Before
  public void before() {

    for (;;) {
      for (final RedisClient client : masterClients) {
        client.skip().sendCmd(Cmds.FLUSHALL);
        client.skip().clusterReset(Cmds.SOFT);
      }

      for (int i = 0; i < NUM_MASTERS; i++) {
        final RedisClient client = masterClients[i];
        client.clusterAddSlots(slots[i]);

        for (final Node meetNode : slaves) {
          client.skip().clusterMeet(meetNode.getHost(), meetNode.getPort());
        }

        masterClients[(i == 0 ? NUM_MASTERS : i) - 1].skip().clusterMeet(client.getHost(),
            client.getPort());
      }

      if (waitForClusterReady(masterClients)) {
        return;
      }

      log.warning("Timed out setting up cluster for test, trying again...");
      for (final Node node : slaves) {
        try (final RedisClient client = REDIS_CLIENT_BUILDER.create(node)) {
          client.clusterReset(Cmds.SOFT);
        }
      }
    }
  }

  @After
  public void after() {

    for (;;) {
      final Node node = pendingReset.poll();
      if (node == null) {
        break;
      }

      try (final RedisClient client = RedisClientFactory.startBuilding().create(node)) {
        client.skip().sendCmd(Cmds.FLUSHALL);
        client.clusterReset(Cmds.SOFT);
      }
    }
  }

  @AfterClass
  public static void afterClass() {

    for (final RedisClient master : masterClients) {
      master.skip().sendCmd(Cmds.FLUSHALL);
      master.clusterReset(Cmds.SOFT);
      master.close();
    }
  }

  static void setUpSlaves(final Map<HostPort, Node> clusterNodes) {

    for (int i = 0; i < NUM_MASTERS; i++) {

      final Node master = clusterNodes.get(masters[i].getHostPort());

      for (int s = i; s < slaves.length; s += NUM_MASTERS) {
        try (final RedisClient slave = RedisClientFactory.startBuilding().create(slaves[s])) {
          slave.clusterReplicate(master.getId());
        }
      }
    }

    try (final RedisClient client = RedisClientFactory.startBuilding().create(masters[0])) {

      for (int i = 0; i < NUM_MASTERS; i++) {

        final Node master = clusterNodes.get(masters[i].getHostPort());

        while (client.clusterSlaves(master.getId()).length != NUM_SLAVES_EACH) {
          try {
            Thread.sleep(7);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        }
      }
    }
  }

  private static boolean waitForClusterReady(final RedisClient[] clients) {

    for (final RedisClient client : clients) {
      if (!waitForClusterReady(client, MAX_WAIT_CLUSTER_READY)) {
        return false;
      }
    }

    return true;
  }

  private static boolean waitForClusterReady(final RedisClient client, final long timeout) {

    for (int slept = 0, sleep = 7; !client.clusterInfo().startsWith("cluster_state:ok"); slept +=
        sleep) {

      if (slept > timeout) {
        return false;
      }

      try {
        Thread.sleep(sleep);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }

    return true;
  }

  private static int rotateSlotNode(final int slot) {

    return (slot + MAX_SLOT_RANGE) % CRC16.NUM_SLOTS;
  }

  @Test(timeout = 3000)
  public void testMovedExceptionParameters() {

    final byte[] key = RESP.toBytes("42");
    final int slot = CRC16.getSlot(key);
    final int invalidSlot = rotateSlotNode(slot);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      final int moveToPort = rce.apply(invalidSlot, invalid -> {

        try {
          invalid.sendCmd(Cmds.SET, key, new byte[0]);
        } catch (final SlotMovedException jme) {

          assertEquals(slot, jme.getSlot());
          return jme.getTargetNode().getPort();
        }

        throw new IllegalStateException(String.format(
            "SlotMovedException was not thrown when executing a %d slot key against a %d slot pool.",
            slot, invalidSlot));
      });

      assertTrue(moveToPort == rce.apply(slot, valid -> valid.getPort()));
    }
  }

  @Test(timeout = 3000)
  public void testThrowAskException() {

    final byte[] key = RESP.toBytes("test");
    final int slot = CRC16.getSlot(key);
    final int importingNodeSlot = rotateSlotNode(slot);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      final Node importing = rce.apply(importingNodeSlot, RedisClient::getNode);

      rce.accept(slot, client -> {

        client.clusterSetSlotMigrating(slot, importing.getId());

        try {
          client.sendCmd(Cmds.GET.raw(), key);
        } catch (final AskNodeException jade) {
          return;
        }

        fail(String.format("Slot %d did not migrate from %s to %s.", slot, client.getNode(),
            importing));
      });
    }
  }

  @Test(timeout = 3000)
  public void testDiscoverNodesAutomatically() {

    try (final RedisClient client = RedisClientFactory.startBuilding().create(masters[0])) {

      setUpSlaves(client.getClusterNodeMap());
    }

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).withReadMode(ReadMode.MIXED).create()) {

      final int[] numNodes = new int[1];
      rce.acceptAllMasters(master -> numNodes[0]++);
      assertEquals(NUM_MASTERS, numNodes[0]);

      numNodes[0] = 0;
      rce.acceptAllSlaves(slave -> numNodes[0]++);
      assertEquals(NUM_SLAVES, numNodes[0]);
    }
  }

  @Test(timeout = 3000)
  public void testReadonly() {

    try (final RedisClient client = RedisClientFactory.startBuilding().create(masters[0])) {

      setUpSlaves(client.getClusterNodeMap());
    }

    final byte[] key = RESP.toBytes("ro");
    final int slot = CRC16.getSlot(key);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).withReadMode(ReadMode.SLAVES).create()) {

      rce.accept(slot, client -> {
        try {
          client.sendCmd(Cmds.SET, key, new byte[0]);
          fail();
        } catch (final SlotMovedException e) {
          client.sendCmd(Cmds.GET.raw(), key);
        }
      });
    }
  }

  @Test(timeout = 3000)
  public void testMigrate() {

    final String keyString = "MIGRATE";
    final byte[] key = RESP.toBytes(keyString);
    final int slot = CRC16.getSlot(key);
    final int importingNodeSlot = rotateSlotNode(slot);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      final Node exporting = rce.apply(slot, RedisClient::getNode);
      final Node importing = rce.apply(importingNodeSlot, client -> {
        client.clusterSetSlotImporting(slot, exporting.getId());
        return client.getNode();
      });

      rce.accept(slot, client -> client.clusterSetSlotMigrating(slot, importing.getId()));

      rce.accept(importingNodeSlot, client -> {
        try {
          client.sendCmd(Cmds.SET, key, new byte[0]);
          fail(
              "SlotMovedException was not thrown after accessing a slot-importing node on first try.");
        } catch (final SlotMovedException jme) {
          assertEquals(slot, jme.getSlot());
          assertEquals(exporting.getPort(), jme.getTargetNode().getPort());
        }
      });

      rce.accept(slot, client -> {
        try {
          client.sendCmd(Cmds.SET, key, new byte[0]);
          fail(
              "AskNodeException was not thrown after accessing a slot-migrating node on first try.");
        } catch (final AskNodeException jae) {
          assertEquals(slot, jae.getSlot());
          assertEquals(importing.getPort(), jae.getTargetNode().getPort());
        }
      });

      rce.accept(slot, client -> client.sendCmd(Cmds.SET, keyString, "val"));

      rce.accept(importingNodeSlot, client -> {
        try {
          client.sendCmd(Cmds.GET.raw(), key);
          fail(
              "SlotMovedException was not thrown after accessing a slot-importing node on first try.");
        } catch (final SlotMovedException jme) {
          assertEquals(slot, jme.getSlot());
          assertEquals(exporting.getPort(), jme.getTargetNode().getPort());
        }
      });

      rce.accept(slot, client -> {
        try {
          client.sendCmd(Cmds.GET.raw(), key);
          fail(
              "AskNodeException was not thrown after accessing a slot-migrating node on first try.");
        } catch (final AskNodeException jae) {
          assertEquals(slot, jae.getSlot());
          assertEquals(importing.getPort(), jae.getTargetNode().getPort());
        }
      });

      assertEquals("val", rce.apply(slot, client -> client.sendCmd(Cmds.GET, keyString)));
      rce.accept(importingNodeSlot, client -> client.clusterSetSlotNode(slot, client.getNodeId()));
      assertEquals("val",
          rce.apply(importingNodeSlot, client -> client.sendCmd(Cmds.GET, keyString)));

      rce.accept(slot, migrated -> {
        migrated.sendCmd(Cmds.GET.raw(), key);
        assertEquals(importing, migrated.getNode());
      });
    }
  }

  @Test(timeout = 4000)
  public void testMigrateToNewNode() {

    final String keyString = "MIGRATE";
    final byte[] key = RESP.toBytes(keyString);
    final int slot = CRC16.getSlot(key);
    final Node newNode = slaves[0];

    try (final RedisClient client = RedisClientFactory.startBuilding().create(newNode)) {

      do {
        client.clusterReset(Cmds.HARD);
        pendingReset.add(newNode);
        for (final Node master : masters) {
          client.clusterMeet(master.getHost(), master.getPort());
        }
      } while (!waitForClusterReady(client, 2000));
    }

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      final Node exporting = rce.apply(slot, RedisClient::getNode);
      final Node importing = rce.applyUnknown(newNode, client -> {
        client.clusterSetSlotImporting(slot, exporting.getId());
        client.getNodeId();
        return client.getNode();
      });

      rce.accept(slot, client -> client.clusterSetSlotMigrating(slot, importing.getId()));

      rce.acceptUnknown(newNode, client -> {
        try {
          client.sendCmd(Cmds.SET, key, new byte[0]);
          fail(
              "SlotMovedException was not thrown after accessing a slot-importing node on first try.");
        } catch (final SlotMovedException jme) {
          assertEquals(slot, jme.getSlot());
          assertEquals(exporting.getPort(), jme.getTargetNode().getPort());
        }
      });

      rce.accept(slot, client -> {
        try {
          client.sendCmd(Cmds.SET, key, new byte[0]);
          fail(
              "AskNodeException was not thrown after accessing a slot-migrating node on first try.");
        } catch (final AskNodeException jae) {
          assertEquals(slot, jae.getSlot());
          assertEquals(importing.getPort(), jae.getTargetNode().getPort());
        }
      });

      rce.accept(slot, client -> client.sendCmd(Cmds.SET, keyString, "val"));

      rce.acceptUnknown(newNode, client -> {
        try {
          client.sendCmd(Cmds.GET.raw(), key);
          fail(
              "SlotMovedException was not thrown after accessing a slot-importing node on first try.");
        } catch (final SlotMovedException jme) {
          assertEquals(slot, jme.getSlot());
          assertEquals(exporting.getPort(), jme.getTargetNode().getPort());
        }
      });

      rce.accept(slot, client -> {
        try {
          client.sendCmd(Cmds.GET.raw(), key);
          fail(
              "AskNodeException was not thrown after accessing a slot-migrating node on first try.");
        } catch (final AskNodeException jae) {
          assertEquals(slot, jae.getSlot());
          assertEquals(importing.getPort(), jae.getTargetNode().getPort());
        }
      });

      assertEquals("val", rce.apply(slot, client -> client.sendCmd(Cmds.GET, keyString)));
      rce.acceptUnknown(newNode, client -> client.clusterSetSlotNode(slot, client.getNodeId()));
      assertEquals("val", rce.applyUnknown(newNode, client -> client.sendCmd(Cmds.GET, keyString)));

      rce.accept(slot, migrated -> {
        migrated.sendCmd(Cmds.GET.raw(), key);
        assertEquals(newNode, migrated.getNode());
      });
    }
  }

  @Test
  public void testAskResponse() {

    final String key = "42";
    final int slot = CRC16.getSlot(key);
    final int importingNodeSlot = rotateSlotNode(slot);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      final String exporting = rce.apply(slot, RedisClient::getNodeId);
      final String importing = rce.apply(importingNodeSlot, client -> {
        client.clusterSetSlotImporting(slot, exporting);
        return client.getNodeId();
      });

      rce.accept(slot, client -> client.clusterSetSlotMigrating(slot, importing));

      rce.accept(slot, client -> client.sendCmd(Cmds.SADD.prim(), key, "107.6"));

      final long numMembers = rce.apply(slot, client -> client.sendCmd(Cmds.SCARD.prim(), key));
      assertEquals(1, numMembers);

      try {
        rce.acceptPipeline(slot, pipeline -> {
          pipeline.sendCmd(Cmds.SADD.prim(), key, "107.6");
          final FutureLongReply futureReply = pipeline.sendCmd(Cmds.SADD.prim(), key, "107.6");
          // Jedipus throws an UnhandledAskNodeException here because each KEY CMD needs to ASK.
          // UnhandledAskNodeException is a RedisRetryableUnhandledException, which depending on the
          // RedisClusterExecutor configuration, may be retried just like a connection exception.
          pipeline.sync();
          assertEquals(0, futureReply.getLong());
        });
      } catch (final UnhandledAskNodeException unhandledAsk) {
        rce.acceptPipelinedIfPresent(unhandledAsk.getTargetNode(), pipeline -> {
          pipeline.skip().asking();
          pipeline.sendCmd(Cmds.SADD.prim(), key, "107.6");
          pipeline.skip().asking();
          final FutureLongReply futureReply = pipeline.sendCmd(Cmds.SADD.prim(), key, "107.6");
          pipeline.sync();
          assertEquals(0, futureReply.getLong());
        });
      }
    }
  }

  @Test(expected = MaxRedirectsExceededException.class)
  public void testRedisClusterMaxRedirections() {

    final byte[] key = RESP.toBytes("42");
    final int slot = CRC16.getSlot(key);
    final int importingNodeSlot = rotateSlotNode(slot);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      final String importing = rce.apply(importingNodeSlot, RedisClient::getNodeId);
      rce.accept(slot, exporting -> exporting.clusterSetSlotMigrating(slot, importing));
      rce.accept(slot, client -> client.sendCmd(Cmds.SET, key, new byte[0]));
    }
  }

  @Test(timeout = 3000)
  public void testClusterForgetNode() throws InterruptedException {

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).withReadMode(ReadMode.MIXED).create()) {

      try (final RedisClient client = RedisClientFactory.startBuilding().create(slaves[0])) {

        rce.acceptAll(node -> assertTrue(node.clusterNodes().contains(client.getNodeId())),
            ForkJoinPool.commonPool()).forEach(CompletableFuture::join);
        rce.acceptAll(node -> node.clusterForget(client.getNodeId()), ForkJoinPool.commonPool())
            .forEach(CompletableFuture::join);
        rce.acceptAll(node -> assertFalse(node.clusterNodes().contains(client.getNodeId())),
            ForkJoinPool.commonPool()).forEach(CompletableFuture::join);
      }
    }
  }

  @Test(timeout = 3000)
  public void testClusterFlushSlots() {

    final byte[] key = RESP.toBytes("42");
    final int slot = CRC16.getSlot(key);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).withReadMode(ReadMode.MIXED).create()) {

      final Node node = rce.apply(ReadMode.MASTER, slot, client -> {
        client.clusterFlushSlots();
        return client.getNode();
      });

      try {
        rce.accept(ReadMode.MASTER, slot, client -> client.sendCmd(Cmds.SET, key, new byte[0]));
      } catch (final RedisClusterDownException downEx) {
        assertTrue(downEx.getMessage().startsWith("CLUSTERDOWN"));
      }

      rce.acceptIfPresent(node, client -> client
          .clusterAddSlots(slots[(int) ((slot / (double) CRC16.NUM_SLOTS) * slots.length)]));

      rce.accept(ReadMode.MASTER, slot, client -> client.sendCmd(Cmds.SET, key, new byte[0]));
    }
  }

  @Test(timeout = 3000)
  public void testClusterKeySlot() {

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      rce.accept(client -> {
        assertEquals(client.clusterKeySlot("foo{bar}zap}"), CRC16.getSlot("foo{bar}zap"));
        assertEquals(client.clusterKeySlot("{user1000}.following"),
            CRC16.getSlot("{user1000}.following"));
      });
    }
  }

  @Test(timeout = 3000)
  public void testClusterCountKeysInSlot() {

    final int slot = CRC16.getSlot("foo{bar}");

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      rce.accept(slot, client -> {
        IntStream.range(0, 5).forEach(index -> client.sendCmd(Cmds.SET, "foo{bar}" + index, "v"));
        assertEquals(5, client.clusterCountKeysInSlot(slot));
      });
    }
  }

  @Test(timeout = 3000)
  public void testStableSlotWhenMigratingNodeOrImportingNodeIsNotSpecified() {

    final String keyString = "42";
    final byte[] key = RESP.toBytes(keyString);
    final int slot = CRC16.getSlot(key);
    final int importingNodeSlot = rotateSlotNode(slot);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      final String exporting = rce.apply(slot, client -> {
        client.skip().sendCmd(Cmds.SET, keyString, "107.6");
        return client.getNodeId();
      });

      final String importing = rce.apply(importingNodeSlot, client -> {
        client.clusterSetSlotImporting(slot, exporting);
        return client.getNodeId();
      });

      assertEquals("107.6", rce.apply(slot, client -> client.sendCmd(Cmds.GET, keyString)));
      rce.accept(importingNodeSlot, client -> client.clusterSetSlotStable(slot));
      assertEquals("107.6", rce.apply(slot, client -> client.sendCmd(Cmds.GET, keyString)));

      rce.accept(slot, client -> client.clusterSetSlotMigrating(slot, importing));
      assertEquals("107.6", rce.apply(slot, client -> client.sendCmd(Cmds.GET, keyString)));
      rce.accept(slot, client -> client.clusterSetSlotStable(slot));
      assertEquals("107.6", rce.apply(slot, client -> client.sendCmd(Cmds.GET, keyString)));
    }
  }

  @Test(timeout = 200, expected = NoSuchElementException.class)
  public void testIfPoolConfigAppliesToClusterPools() {

    final Function<Node, ClientPool<RedisClient>> poolFactory = node -> ClientPool.startBuilding()
        .withMaxTotal(0).withMaxWaitDuration(Duration.ofMillis(20)).withBlockWhenExhausted(true)
        .create(RedisClientFactory.startBuilding().createPooled(node));

    try (final RedisClusterExecutor rce = RedisClusterExecutor.startBuilding(discoveryNodes)
        .withMasterPoolFactory(poolFactory).create()) {

      rce.accept(client -> client.sendCmd(Cmds.SET, "42", "107.6"));
    }
  }

  @Test(timeout = 3000)
  public void testCloseable() {

    final RedisClusterExecutor rce = RedisClusterExecutor.startBuilding(discoveryNodes).create();
    try {
      rce.acceptAll(client -> assertEquals("PONG", client.sendCmd(Cmds.PING)),
          ForkJoinPool.commonPool()).forEach(CompletableFuture::join);
    } finally {
      rce.close();
    }

    rce.acceptAll(client -> fail("All pools should have been closed."));

    try {
      rce.accept(client -> client.sendCmd(Cmds.PING));
      fail("All pools should have been closed.");
    } catch (final RedisConnectionException jcex) {
      // expected
    }
  }

  @Test(timeout = 3000)
  public void testRedisClusterClientTimeout() {

    final RedisClientFactory.Builder poolFactoryBuilder =
        RedisClientFactory.startBuilding().withConnTimeout(1234).withSoTimeout(4321);

    final Function<Node, ClientPool<RedisClient>> poolFactory =
        node -> ClientPool.startBuilding().create(poolFactoryBuilder.createPooled(node));

    try (final RedisClusterExecutor rce = RedisClusterExecutor.startBuilding(discoveryNodes)
        .withMasterPoolFactory(poolFactory).create()) {

      rce.accept(client -> {
        assertEquals(1234, poolFactoryBuilder.getConnTimeout());
        assertEquals(4321, client.getSoTimeout());
      });
    }
  }

  @Test(timeout = 3000)
  public void testRedisClusterRunsWithMultithreaded()
      throws InterruptedException, ExecutionException {


    final Function<Node, ClientPool<RedisClient>> poolFactory = node -> ClientPool.startBuilding()
        .create(RedisClientFactory.startBuilding().createPooled(node));

    final int numThreads = Runtime.getRuntime().availableProcessors() * 2;
    final ThreadPoolExecutor executor = new ThreadPoolExecutor(numThreads, numThreads,
        Long.MAX_VALUE, TimeUnit.NANOSECONDS, new SynchronousQueue<>(), (task, exec) -> task.run());

    final String keyString = "42";
    final byte[] key = RESP.toBytes(keyString);
    final int slot = CRC16.getSlot(key);

    try (final RedisClusterExecutor rce = RedisClusterExecutor.startBuilding(discoveryNodes)
        .withMasterPoolFactory(poolFactory).create()) {

      final int numSets = 200;
      final List<Future<String>> futures = new ArrayList<>(numSets);
      for (int i = 0; i < numSets; i++) {

        final byte[] val = RESP.toBytes(i);

        final Future<String> future = executor.submit(() -> rce.applyPipeline(slot, pipeline -> {
          pipeline.skip().sendCmd(Cmds.SET, key, val);
          final FutureReply<String> futureReply = pipeline.sendCmd(Cmds.GET, key);
          pipeline.sync();
          return futureReply.get();
        }));

        futures.add(future);
      }

      int count = 0;
      for (final Future<String> future : futures) {
        assertEquals(String.valueOf(count++), future.get());
      }
    }
  }

  static final Cmd<Object> CLIENT = Cmd.createCast("CLIENT");
  static final Cmd<String> CLIENT_KILL = Cmd.createStringReply("KILL");

  @Test(timeout = 3000)
  public void testReturnConnectionOnRedisConnectionException() {

    final String keyString = "42";
    final byte[] key = RESP.toBytes(keyString);
    final int slot = CRC16.getSlot(key);

    final Function<Node, ClientPool<RedisClient>> poolFactory = node -> ClientPool.startBuilding()
        .withMaxTotal(1).create(RedisClientFactory.startBuilding().createPooled(node));

    try (final RedisClusterExecutor rce = RedisClusterExecutor.startBuilding(discoveryNodes)
        .withMasterPoolFactory(poolFactory).create()) {

      rce.accept(slot, client -> {

        client.skip().setClientName("DEAD");

        for (final String clientInfo : client.getClientList()) {

          final int nameStart = clientInfo.indexOf("name=") + 5;
          if (clientInfo.substring(nameStart, nameStart + 4).equals("DEAD")) {

            final int addrStart = clientInfo.indexOf("addr=") + 5;
            final int addrEnd = clientInfo.indexOf(' ', addrStart);
            client.sendCmd(CLIENT, CLIENT_KILL,
                RESP.toBytes(clientInfo.substring(addrStart, addrEnd)));
            break;
          }
        }
      });

      assertEquals("PONG", rce.apply(slot, client -> client.sendCmd(Cmds.PING)));
    }
  }

  @Test(timeout = 3000, expected = MaxRedirectsExceededException.class)
  public void testReturnConnectionOnRedirection() {

    final byte[] key = RESP.toBytes("42");
    final int slot = CRC16.getSlot(key);
    final int importingNodeSlot = rotateSlotNode(slot);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(discoveryNodes).create()) {

      final String importing = rce.apply(importingNodeSlot, RedisClient::getNodeId);
      rce.accept(slot, client -> client.clusterSetSlotMigrating(slot, importing));
      rce.accept(slot, client -> client.sendCmd(Cmds.GET, key));
    }
  }

  @Test(timeout = 3000)
  public void testLocalhostNodeNotAddedWhen127Present() {

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(Node.create("localhost", STARTING_PORT)).create()) {

      final int[] count = new int[1];
      rce.acceptAll(client -> {
        assertNotEquals("localhost", client.getHost());
        count[0]++;
      });
      assertEquals(NUM_MASTERS, count[0]);
    }
  }

  @Test(timeout = 3000)
  public void testInvalidStartNodeNotAdded() {

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(Node.create("not-a-real-host", STARTING_PORT),
            Node.create("127.0.0.1", STARTING_PORT)).create()) {

      final int[] count = new int[1];
      rce.acceptAll(client -> {
        assertNotEquals("not-a-real-host", client.getHost());
        count[0]++;
      });
      assertEquals(NUM_MASTERS, count[0]);
    }
  }

  @Test
  public void testPipelinedTransaction() {

    final String key = "42";
    final int slot = CRC16.getSlot(key);

    try (final RedisClusterExecutor rce =
        RedisClusterExecutor.startBuilding(Node.create("localhost", STARTING_PORT)).create()) {

      final String[] bitfieldOverflowExample = new String[] {key, Cmds.BITFIELD_INCRBY.name(), "u2",
          "100", "1", Cmds.BITFIELD_OVERFLOW.name(), Cmds.BITFIELD_SAT.name(),
          Cmds.BITFIELD_INCRBY.name(), "u2", "102", "1"};

      rce.acceptPipelinedTransaction(slot, pipeline -> {

        final FutureReply<long[]> fr1 =
            pipeline.sendCmd(Cmds.BITFIELD.primArray(), bitfieldOverflowExample);
        pipeline.sendCmd(Cmds.BITFIELD.primArray(), bitfieldOverflowExample);
        pipeline.sendCmd(Cmds.BITFIELD.primArray(), bitfieldOverflowExample);
        final FutureReply<long[]> fr4 =
            pipeline.sendCmd(Cmds.BITFIELD.primArray(), bitfieldOverflowExample);

        int expected = 1;
        for (final long[] reply : pipeline.primArrayExecSyncThrow().get()) {
          assertEquals(expected % 4, reply[0]);
          assertEquals(Math.min(3, expected++), reply[1]);
        }

        assertEquals(1, fr1.get()[0]);
        assertEquals(1, fr1.get()[1]);
        assertEquals(0, fr4.get()[0]);
        assertEquals(3, fr4.get()[1]);
      });
    }
  }
}
