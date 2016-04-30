package com.fabahaba.jedipus.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;

import com.fabahaba.jedipus.HostPort;
import com.fabahaba.jedipus.RESP;
import com.fabahaba.jedipus.cluster.JedisClusterExecutor.ReadMode;

import redis.clients.jedis.BinaryJedisCluster;
import redis.clients.jedis.Client;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

class JedisClusterSlotCache implements AutoCloseable {

  private final ReadMode defaultReadMode;

  private final Set<HostAndPort> discoveryHostPorts;

  protected final Map<HostAndPort, JedisPool> masterPools;
  private final JedisPool[] masterSlots;

  private final Function<JedisPool[], LoadBalancedPools> lbFactory;
  protected final Map<HostAndPort, JedisPool> slavePools;
  private final LoadBalancedPools[] slaveSlots;

  private final ConcurrentMap<Jedis, Boolean> roInitializedClients;

  private final boolean optimisticReads;
  private final StampedLock lock;
  private final long millisBetweenSlotCacheRefresh;
  private volatile long refreshStamp = 0;

  private final Function<HostAndPort, JedisPool> masterPoolFactory;
  private final Function<HostAndPort, JedisPool> slavePoolFactory;
  protected final Function<HostAndPort, Jedis> jedisAskDiscoveryFactory;

  private static final int MASTER_NODE_INDEX = 2;

  JedisClusterSlotCache(final ReadMode defaultReadMode, final boolean optimisticReads,
      final Duration durationBetweenSlotCacheRefresh, final Set<HostAndPort> discoveryNodes,
      final Map<HostAndPort, JedisPool> masterPools, final JedisPool[] masterSlots,
      final Map<HostAndPort, JedisPool> slavePools, final LoadBalancedPools[] slaveSlots,
      final Function<HostAndPort, JedisPool> masterPoolFactory,
      final Function<HostAndPort, JedisPool> slavePoolFactory,
      final Function<HostAndPort, Jedis> jedisAskDiscoveryFactory,
      final Function<JedisPool[], LoadBalancedPools> lbFactory, final boolean initReadOnly) {

    this.refreshStamp = System.currentTimeMillis();

    this.defaultReadMode = defaultReadMode;
    this.discoveryHostPorts = discoveryNodes;

    this.masterPools = masterPools;
    this.masterSlots = masterSlots;

    this.slavePools = slavePools;
    this.slaveSlots = slaveSlots;

    this.roInitializedClients =
        !initReadOnly || defaultReadMode == ReadMode.MASTER ? null : new ConcurrentHashMap<>();

    this.optimisticReads = optimisticReads;
    this.millisBetweenSlotCacheRefresh = durationBetweenSlotCacheRefresh.toMillis();
    this.lock = new StampedLock();

    this.masterPoolFactory = masterPoolFactory;
    this.slavePoolFactory = slavePoolFactory;
    this.jedisAskDiscoveryFactory = jedisAskDiscoveryFactory;
    this.lbFactory = lbFactory;
  }

  ReadMode getDefaultReadMode() {

    return defaultReadMode;
  }

  static JedisClusterSlotCache create(final ReadMode defaultReadMode, final boolean optimisticReads,
      final Duration durationBetweenSlotCacheRefresh,
      final Collection<HostAndPort> discoveryHostPorts,
      final Function<HostAndPort, JedisPool> masterPoolFactory,
      final Function<HostAndPort, JedisPool> slavePoolFactory,
      final Function<HostAndPort, Jedis> jedisAskDiscoveryFactory,
      final Function<JedisPool[], LoadBalancedPools> lbFactory, final boolean initReadOnly) {

    final Map<HostAndPort, JedisPool> masterPools =
        defaultReadMode == ReadMode.SLAVES ? Collections.emptyMap() : new ConcurrentHashMap<>();
    final JedisPool[] masterSlots = defaultReadMode == ReadMode.SLAVES ? new JedisPool[0]
        : new JedisPool[BinaryJedisCluster.HASHSLOTS];

    final Map<HostAndPort, JedisPool> slavePools =
        defaultReadMode == ReadMode.MASTER ? Collections.emptyMap() : new ConcurrentHashMap<>();
    final LoadBalancedPools[] slaveSlots = defaultReadMode == ReadMode.MASTER
        ? new LoadBalancedPools[0] : new LoadBalancedPools[BinaryJedisCluster.HASHSLOTS];

    return create(defaultReadMode, optimisticReads, durationBetweenSlotCacheRefresh,
        discoveryHostPorts, masterPoolFactory, slavePoolFactory, jedisAskDiscoveryFactory,
        lbFactory, masterPools, masterSlots, slavePools, slaveSlots, initReadOnly);
  }

  @SuppressWarnings("unchecked")
  private static JedisClusterSlotCache create(final ReadMode defaultReadMode,
      final boolean optimisticReads, final Duration durationBetweenSlotCacheRefresh,
      final Collection<HostAndPort> discoveryHostPorts,
      final Function<HostAndPort, JedisPool> masterPoolFactory,
      final Function<HostAndPort, JedisPool> slavePoolFactory,
      final Function<HostAndPort, Jedis> jedisAskDiscoveryFactory,
      final Function<JedisPool[], LoadBalancedPools> lbFactory,
      final Map<HostAndPort, JedisPool> masterPools, final JedisPool[] masterSlots,
      final Map<HostAndPort, JedisPool> slavePools, final LoadBalancedPools[] slaveSlots,
      final boolean initReadOnly) {

    final Set<HostAndPort> allDiscoveryHostPorts =
        Collections.newSetFromMap(new ConcurrentHashMap<>(discoveryHostPorts.size()));
    allDiscoveryHostPorts.addAll(discoveryHostPorts);

    for (final HostAndPort discoveryHostPort : discoveryHostPorts) {

      try (final Jedis jedis = jedisAskDiscoveryFactory.apply(discoveryHostPort)) {

        final List<Object> slots = jedis.clusterSlots();

        for (final Object slotInfoObj : slots) {

          final List<Object> slotInfo = (List<Object>) slotInfoObj;

          final int slotBegin = RESP.longToInt(slotInfo.get(0));
          final int slotEnd = RESP.longToInt(slotInfo.get(1));

          switch (defaultReadMode) {
            case MIXED_SLAVES:
            case MIXED:
            case MASTER:
              final HostAndPort masterHostPort =
                  createHostPort((List<Object>) slotInfo.get(MASTER_NODE_INDEX));
              allDiscoveryHostPorts.add(masterHostPort);

              final JedisPool masterPool = masterPoolFactory.apply(masterHostPort);
              masterPools.put(masterHostPort, masterPool);

              Arrays.fill(masterSlots, slotBegin, slotEnd, masterPool);
              break;
            case SLAVES:
            default:
              break;
          }

          final ArrayList<JedisPool> slotSlavePools =
              defaultReadMode == ReadMode.MASTER ? null : new ArrayList<>(2);

          for (int i = MASTER_NODE_INDEX + 1, slotInfoSize =
              slotInfo.size(); i < slotInfoSize; i++) {

            final HostAndPort slaveHostPort = createHostPort((List<Object>) slotInfo.get(i));
            allDiscoveryHostPorts.add(slaveHostPort);

            switch (defaultReadMode) {
              case SLAVES:
              case MIXED:
              case MIXED_SLAVES:
                final JedisPool slavePool = slavePoolFactory.apply(slaveHostPort);
                slavePools.put(slaveHostPort, slavePool);
                slotSlavePools.add(slavePool);
                break;
              case MASTER:
              default:
                break;
            }
          }

          if (defaultReadMode != ReadMode.MASTER) {

            final LoadBalancedPools lbPools =
                lbFactory.apply(slotSlavePools.toArray(new JedisPool[slotSlavePools.size()]));

            Arrays.fill(slaveSlots, slotBegin, slotEnd, lbPools);
          }
        }

        if (optimisticReads) {
          return new OptimisticJedisClusterSlotCache(defaultReadMode,
              durationBetweenSlotCacheRefresh, allDiscoveryHostPorts, masterPools, masterSlots,
              slavePools, slaveSlots, masterPoolFactory, slavePoolFactory, jedisAskDiscoveryFactory,
              lbFactory, initReadOnly);
        }

        return new JedisClusterSlotCache(defaultReadMode, optimisticReads,
            durationBetweenSlotCacheRefresh, allDiscoveryHostPorts, masterPools, masterSlots,
            slavePools, slaveSlots, masterPoolFactory, slavePoolFactory, jedisAskDiscoveryFactory,
            lbFactory, initReadOnly);
      } catch (final JedisConnectionException e) {
        // try next discoveryNode...
      }
    }

    if (optimisticReads) {
      return new OptimisticJedisClusterSlotCache(defaultReadMode, durationBetweenSlotCacheRefresh,
          allDiscoveryHostPorts, masterPools, masterSlots, slavePools, slaveSlots,
          masterPoolFactory, slavePoolFactory, jedisAskDiscoveryFactory, lbFactory, initReadOnly);
    }

    return new JedisClusterSlotCache(defaultReadMode, optimisticReads,
        durationBetweenSlotCacheRefresh, allDiscoveryHostPorts, masterPools, masterSlots,
        slavePools, slaveSlots, masterPoolFactory, slavePoolFactory, jedisAskDiscoveryFactory,
        lbFactory, initReadOnly);
  }

  void discoverClusterSlots() {

    for (final HostAndPort discoveryHostPort : getDiscoveryHostPorts()) {

      try (final Jedis jedis = jedisAskDiscoveryFactory.apply(discoveryHostPort)) {

        discoverClusterSlots(jedis);
        return;
      } catch (final JedisConnectionException e) {
        // try next nodes
      }
    }
  }

  @SuppressWarnings("unchecked")
  void discoverClusterSlots(final Jedis jedis) {

    final long dedupeDiscovery = refreshStamp;
    final long writeStamp = lock.writeLock();
    try {

      if (dedupeDiscovery != refreshStamp) {
        return;
      }

      if (!optimisticReads) {
        Arrays.fill(masterSlots, null);
        Arrays.fill(slaveSlots, null);
      }

      final Set<HostAndPort> staleMasterPools = new HashSet<>(masterPools.keySet());
      final Set<HostAndPort> staleSlavePools = new HashSet<>(slavePools.keySet());

      final long delayMillis =
          (refreshStamp + millisBetweenSlotCacheRefresh) - System.currentTimeMillis();

      if (delayMillis > 0) {
        try {
          Thread.sleep(delayMillis);
        } catch (final InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(ie);
        }
      }

      final List<Object> slots = jedis.clusterSlots();

      for (final Object slotInfoObj : slots) {

        final List<Object> slotInfo = (List<Object>) slotInfoObj;

        final int slotBegin = RESP.longToInt(slotInfo.get(0));
        final int slotEnd = RESP.longToInt(slotInfo.get(1));

        switch (defaultReadMode) {
          case MIXED_SLAVES:
          case MIXED:
          case MASTER:
            final HostAndPort masterHostPort =
                createHostPort((List<Object>) slotInfo.get(MASTER_NODE_INDEX));
            discoveryHostPorts.add(masterHostPort);

            final JedisPool masterPool = masterPoolFactory.apply(masterHostPort);
            masterPools.put(masterHostPort, masterPool);

            Arrays.fill(masterSlots, slotBegin, slotEnd, masterPool);
            break;
          case SLAVES:
          default:
            break;
        }

        final ArrayList<JedisPool> slotSlavePools =
            defaultReadMode == ReadMode.MASTER ? null : new ArrayList<>(2);

        for (int i = MASTER_NODE_INDEX + 1, slotInfoSize = slotInfo.size(); i < slotInfoSize; i++) {

          final HostAndPort slaveHostPort = createHostPort((List<Object>) slotInfo.get(i));
          discoveryHostPorts.add(slaveHostPort);

          switch (defaultReadMode) {
            case SLAVES:
            case MIXED:
            case MIXED_SLAVES:
              staleSlavePools.remove(slaveHostPort);

              final JedisPool slavePool =
                  slavePools.computeIfAbsent(slaveHostPort, slavePoolFactory);
              slotSlavePools.add(slavePool);
              break;
            case MASTER:
            default:
              break;
          }
        }

        if (defaultReadMode != ReadMode.MASTER) {

          final LoadBalancedPools lbPools =
              lbFactory.apply(slotSlavePools.toArray(new JedisPool[slotSlavePools.size()]));

          Arrays.fill(slaveSlots, slotBegin, slotEnd, lbPools);
        }
      }

      staleMasterPools.stream().map(masterPools::remove).filter(Objects::nonNull).forEach(pool -> {
        try {
          pool.close();
        } catch (final RuntimeException e) {
          // closing anyways...
        }
      });

      staleSlavePools.stream().map(slavePools::remove).filter(Objects::nonNull).forEach(pool -> {
        try {
          pool.close();
        } catch (final RuntimeException e) {
          // closing anyways...
        }
      });

      if (isInitReadOnly()) {
        roInitializedClients.forEach((client, isRO) -> {
          if (!client.isConnected()) {
            roInitializedClients.remove(client);
          }
        });
      }
    } finally {
      try {
        refreshStamp = System.currentTimeMillis();
      } finally {
        lock.unlockWrite(writeStamp);
      }
    }
  }

  private static HostAndPort createHostPort(final List<Object> hostInfos) {

    return HostPort.createHostPort(RESP.toString(hostInfos.get(0)),
        RESP.longToInt(hostInfos.get(1)));
  }

  static HostAndPort createHostPort(final Jedis jedis) {

    final Client client = jedis.getClient();

    return HostPort.createHostPort(client.getHost(), client.getPort());
  }

  Jedis getAskJedis(final HostAndPort askHostPort) {

    long readStamp = lock.tryOptimisticRead();

    JedisPool pool = getAskJedisGuarded(askHostPort);

    if (!lock.validate(readStamp)) {

      readStamp = lock.readLock();
      try {
        pool = getAskJedisGuarded(askHostPort);
      } finally {
        lock.unlockRead(readStamp);
      }
    }

    return pool == null ? jedisAskDiscoveryFactory.apply(askHostPort) : pool.getResource();
  }

  protected JedisPool getAskJedisGuarded(final HostAndPort askHostPort) {

    switch (defaultReadMode) {
      case MASTER:
        return masterPools.get(askHostPort);
      case MIXED:
      case MIXED_SLAVES:
        JedisPool pool = masterPools.get(askHostPort);

        if (pool == null) {
          pool = slavePools.get(askHostPort);
        }

        return pool;
      case SLAVES:
        return slavePools.get(askHostPort);
      default:
        return null;
    }
  }

  Jedis getSlotConnection(final ReadMode readMode, final int slot) {

    JedisPool pool = null;

    switch (defaultReadMode) {
      case MASTER:
        pool = getSlotPoolModeChecked(defaultReadMode, slot);
        return pool == null ? null : pool.getResource();
      case SLAVES:
        pool = getSlotPoolModeChecked(defaultReadMode, slot);
        break;
      case MIXED:
      case MIXED_SLAVES:
        pool = getSlotPoolModeChecked(readMode, slot);
        break;
      default:
        return null;
    }

    if (pool == null) {
      return null;
    }

    switch (readMode) {
      case MASTER:
        return pool.getResource();
      case MIXED:
      case MIXED_SLAVES:
      case SLAVES:
        final Jedis jedis = pool.getResource();
        if (!isInitReadOnly() || jedis == null) {
          return jedis;
        }

        roInitializedClients.compute(jedis, (client, isRO) -> {
          if (isRO == null) {
            synchronized (jedis) {
              if (!roInitializedClients.containsKey(jedis)) {
                jedis.readonly();
              }
            }
          }

          return Boolean.TRUE;
        });

        return jedis;
      default:
        return null;
    }
  }

  protected JedisPool getSlotPoolModeChecked(final ReadMode readMode, final int slot) {

    long readStamp = lock.tryOptimisticRead();

    final JedisPool pool = getLoadBalancedPool(readMode, slot);

    if (lock.validate(readStamp)) {
      return pool;
    }

    readStamp = lock.readLock();
    try {
      return getLoadBalancedPool(readMode, slot);
    } finally {
      lock.unlockRead(readStamp);
    }
  }

  protected JedisPool getLoadBalancedPool(final ReadMode readMode, final int slot) {

    switch (readMode) {
      case MASTER:
        return masterSlots[slot];
      case MIXED:
      case MIXED_SLAVES:
        LoadBalancedPools lbSlaves = slaveSlots[slot];
        if (lbSlaves == null) {
          return masterSlots[slot];
        }

        final JedisPool slavePool = lbSlaves.next(readMode);

        return slavePool == null ? masterSlots[slot] : slavePool;
      case SLAVES:
        lbSlaves = slaveSlots[slot];
        if (lbSlaves == null) {
          return masterSlots[slot];
        }

        return lbSlaves.next(readMode);
      default:
        return null;
    }
  }

  List<JedisPool> getPools(final ReadMode readMode) {

    switch (defaultReadMode) {
      case MASTER:
      case SLAVES:
        return getPoolsModeChecked(defaultReadMode);
      case MIXED:
      case MIXED_SLAVES:
        return getPoolsModeChecked(readMode);
      default:
        return null;
    }
  }

  private List<JedisPool> getPoolsModeChecked(final ReadMode readMode) {

    switch (readMode) {
      case MASTER:
        return getMasterPools();
      case MIXED:
      case MIXED_SLAVES:
      case SLAVES:
        return getAllPools();
      default:
        return null;
    }
  }

  List<JedisPool> getMasterPools() {

    long readStamp = lock.tryOptimisticRead();

    final List<JedisPool> pools = new ArrayList<>(masterPools.values());

    if (lock.validate(readStamp)) {
      return pools;
    }

    pools.clear();

    readStamp = lock.readLock();
    try {
      pools.addAll(masterPools.values());
      return pools;
    } finally {
      lock.unlockRead(readStamp);
    }
  }

  List<JedisPool> getSlavePools() {

    long readStamp = lock.tryOptimisticRead();

    final List<JedisPool> pools = new ArrayList<>(slavePools.values());

    if (lock.validate(readStamp)) {
      return pools;
    }

    pools.clear();

    readStamp = lock.readLock();
    try {
      pools.addAll(slavePools.values());
      return pools;
    } finally {
      lock.unlockRead(readStamp);
    }
  }

  List<JedisPool> getAllPools() {

    long readStamp = lock.tryOptimisticRead();

    final List<JedisPool> allPools = new ArrayList<>(masterPools.size() + slavePools.size());
    allPools.addAll(masterPools.values());
    allPools.addAll(slavePools.values());

    if (lock.validate(readStamp)) {
      return allPools;
    }

    allPools.clear();

    readStamp = lock.readLock();
    try {
      allPools.addAll(masterPools.values());
      allPools.addAll(slavePools.values());
      return allPools;
    } finally {
      lock.unlockRead(readStamp);
    }
  }

  JedisPool getMasterPoolIfPresent(final HostAndPort hostPort) {

    long readStamp = lock.tryOptimisticRead();

    final JedisPool pool = masterPools.get(hostPort);

    if (lock.validate(readStamp)) {
      return pool;
    }

    readStamp = lock.readLock();
    try {
      return masterPools.get(hostPort);
    } finally {
      lock.unlockRead(readStamp);
    }
  }

  JedisPool getSlavePoolIfPresent(final HostAndPort hostPort) {

    long readStamp = lock.tryOptimisticRead();

    final JedisPool pool = slavePools.get(hostPort);

    if (lock.validate(readStamp)) {
      return pool;
    }

    readStamp = lock.readLock();
    try {
      return slavePools.get(hostPort);
    } finally {
      lock.unlockRead(readStamp);
    }
  }

  JedisPool getPoolIfPresent(final HostAndPort hostPort) {

    long readStamp = lock.tryOptimisticRead();

    JedisPool pool = masterPools.get(hostPort);
    if (pool == null) {
      pool = slavePools.get(hostPort);
    }

    if (lock.validate(readStamp)) {
      return pool;
    }

    readStamp = lock.readLock();
    try {
      pool = masterPools.get(hostPort);
      if (pool == null) {
        pool = slavePools.get(hostPort);
      }
      return pool;
    } finally {
      lock.unlockRead(readStamp);
    }
  }

  Set<HostAndPort> getDiscoveryHostPorts() {

    return discoveryHostPorts;
  }

  boolean isInitReadOnly() {

    return roInitializedClients != null;
  }

  @Override
  public void close() {

    final long writeStamp = lock.writeLock();
    try {

      discoveryHostPorts.clear();

      masterPools.forEach((key, pool) -> {
        try {
          if (pool != null) {
            pool.close();
          }
        } catch (final RuntimeException e) {
          // closing anyways...
        }
      });

      masterPools.clear();

      slavePools.forEach((key, pool) -> {
        try {
          if (pool != null) {
            pool.close();
          }
        } catch (final RuntimeException e) {
          // closing anyways...
        }
      });

      slavePools.clear();
    } finally {
      lock.unlockWrite(writeStamp);
    }

    if (isInitReadOnly()) {
      roInitializedClients.clear();
    }
  }
}
