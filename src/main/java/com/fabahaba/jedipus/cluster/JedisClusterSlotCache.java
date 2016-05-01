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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;

import org.apache.commons.pool2.ObjectPool;

import com.fabahaba.jedipus.HostPort;
import com.fabahaba.jedipus.RESP;
import com.fabahaba.jedipus.cluster.JedisClusterExecutor.ReadMode;

import redis.clients.jedis.BinaryJedisCluster;
import redis.clients.jedis.Client;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

class JedisClusterSlotCache implements AutoCloseable {

  private final ReadMode defaultReadMode;

  private final Set<HostPort> discoveryHostPorts;

  protected final Map<ClusterNode, ObjectPool<Jedis>> masterPools;
  private final ObjectPool<Jedis>[] masterSlots;

  private final Function<ObjectPool<Jedis>[], LoadBalancedPools> lbFactory;
  protected final Map<ClusterNode, ObjectPool<Jedis>> slavePools;
  private final LoadBalancedPools[] slaveSlots;

  private final boolean optimisticReads;
  private final long maxAwaitCacheRefreshNanos;
  private final StampedLock lock;
  private final long millisBetweenSlotCacheRefresh;
  private volatile long refreshStamp = 0;

  private final Function<ClusterNode, ObjectPool<Jedis>> masterPoolFactory;
  private final Function<ClusterNode, ObjectPool<Jedis>> slavePoolFactory;
  protected final Function<HostPort, Jedis> jedisAskDiscoveryFactory;

  JedisClusterSlotCache(final ReadMode defaultReadMode, final boolean optimisticReads,
      final Duration durationBetweenCacheRefresh, final Duration maxAwaitCacheRefresh,
      final Set<HostPort> discoveryNodes, final Map<ClusterNode, ObjectPool<Jedis>> masterPools,
      final ObjectPool<Jedis>[] masterSlots, final Map<ClusterNode, ObjectPool<Jedis>> slavePools,
      final LoadBalancedPools[] slaveSlots,
      final Function<ClusterNode, ObjectPool<Jedis>> masterPoolFactory,
      final Function<ClusterNode, ObjectPool<Jedis>> slavePoolFactory,
      final Function<HostPort, Jedis> jedisAskDiscoveryFactory,
      final Function<ObjectPool<Jedis>[], LoadBalancedPools> lbFactory) {

    this.refreshStamp = System.currentTimeMillis();

    this.defaultReadMode = defaultReadMode;
    this.discoveryHostPorts = discoveryNodes;

    this.masterPools = masterPools;
    this.masterSlots = masterSlots;

    this.slavePools = slavePools;
    this.slaveSlots = slaveSlots;

    this.optimisticReads = optimisticReads;
    this.maxAwaitCacheRefreshNanos = maxAwaitCacheRefresh.toNanos();
    this.millisBetweenSlotCacheRefresh = durationBetweenCacheRefresh.toMillis();
    this.lock = new StampedLock();

    this.masterPoolFactory = masterPoolFactory;
    this.slavePoolFactory = slavePoolFactory;
    this.jedisAskDiscoveryFactory = jedisAskDiscoveryFactory;
    this.lbFactory = lbFactory;
  }

  ReadMode getDefaultReadMode() {

    return defaultReadMode;
  }

  @SuppressWarnings("unchecked")
  static JedisClusterSlotCache create(final ReadMode defaultReadMode, final boolean optimisticReads,
      final Duration durationBetweenCacheRefresh, final Duration maxAwaitCacheRefresh,
      final Collection<HostPort> discoveryHostPorts,
      final Function<ClusterNode, ObjectPool<Jedis>> masterPoolFactory,
      final Function<ClusterNode, ObjectPool<Jedis>> slavePoolFactory,
      final Function<HostPort, Jedis> jedisAskDiscoveryFactory,
      final Function<ObjectPool<Jedis>[], LoadBalancedPools> lbFactory) {

    final Map<ClusterNode, ObjectPool<Jedis>> masterPools =
        defaultReadMode == ReadMode.SLAVES ? Collections.emptyMap() : new ConcurrentHashMap<>();
    final ObjectPool<Jedis>[] masterSlots = defaultReadMode == ReadMode.SLAVES ? new ObjectPool[0]
        : new ObjectPool[BinaryJedisCluster.HASHSLOTS];

    final Map<ClusterNode, ObjectPool<Jedis>> slavePools =
        defaultReadMode == ReadMode.MASTER ? Collections.emptyMap() : new ConcurrentHashMap<>();
    final LoadBalancedPools[] slaveSlots = defaultReadMode == ReadMode.MASTER
        ? new LoadBalancedPools[0] : new LoadBalancedPools[BinaryJedisCluster.HASHSLOTS];

    return create(defaultReadMode, optimisticReads, durationBetweenCacheRefresh,
        maxAwaitCacheRefresh, discoveryHostPorts, masterPoolFactory, slavePoolFactory,
        jedisAskDiscoveryFactory, lbFactory, masterPools, masterSlots, slavePools, slaveSlots);
  }

  @SuppressWarnings("unchecked")
  private static JedisClusterSlotCache create(final ReadMode defaultReadMode,
      final boolean optimisticReads, final Duration durationBetweenCacheRefresh,
      final Duration maxAwaitCacheRefresh, final Collection<HostPort> discoveryHostPorts,
      final Function<ClusterNode, ObjectPool<Jedis>> masterPoolFactory,
      final Function<ClusterNode, ObjectPool<Jedis>> slavePoolFactory,
      final Function<HostPort, Jedis> jedisAskDiscoveryFactory,
      final Function<ObjectPool<Jedis>[], LoadBalancedPools> lbFactory,
      final Map<ClusterNode, ObjectPool<Jedis>> masterPools, final ObjectPool<Jedis>[] masterSlots,
      final Map<ClusterNode, ObjectPool<Jedis>> slavePools, final LoadBalancedPools[] slaveSlots) {

    final Set<HostPort> allDiscoveryHostPorts =
        Collections.newSetFromMap(new ConcurrentHashMap<>(discoveryHostPorts.size()));
    allDiscoveryHostPorts.addAll(discoveryHostPorts);

    for (final HostPort discoveryHostPort : discoveryHostPorts) {

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
              final ClusterNode masterNode = ClusterNode.create((List<Object>) slotInfo.get(2));
              allDiscoveryHostPorts.add(masterNode.getHostPort());

              final ObjectPool<Jedis> masterPool = masterPoolFactory.apply(masterNode);
              masterPools.put(masterNode, masterPool);

              Arrays.fill(masterSlots, slotBegin, slotEnd, masterPool);
              break;
            case SLAVES:
            default:
              break;
          }

          final int slotInfoSize = slotInfo.size();
          if (slotInfoSize < 4) {
            continue;
          }

          final ObjectPool<Jedis>[] slotSlavePools =
              defaultReadMode == ReadMode.MASTER ? null : new ObjectPool[slotInfoSize - 3];

          for (int i = 3, poolIndex = 0; i < slotInfoSize; i++) {

            final ClusterNode slaveNode = ClusterNode.create((List<Object>) slotInfo.get(i));
            allDiscoveryHostPorts.add(slaveNode.getHostPort());

            switch (defaultReadMode) {
              case SLAVES:
              case MIXED:
              case MIXED_SLAVES:
                final ObjectPool<Jedis> slavePool = slavePoolFactory.apply(slaveNode);
                slavePools.put(slaveNode, slavePool);
                slotSlavePools[poolIndex++] = slavePool;
                break;
              case MASTER:
              default:
                break;
            }
          }

          if (defaultReadMode != ReadMode.MASTER) {

            final LoadBalancedPools lbPools = lbFactory.apply(slotSlavePools);

            Arrays.fill(slaveSlots, slotBegin, slotEnd, lbPools);
          }
        }

        if (optimisticReads) {
          return new OptimisticJedisClusterSlotCache(defaultReadMode, durationBetweenCacheRefresh,
              maxAwaitCacheRefresh, allDiscoveryHostPorts, masterPools, masterSlots, slavePools,
              slaveSlots, masterPoolFactory, slavePoolFactory, jedisAskDiscoveryFactory, lbFactory);
        }

        return new JedisClusterSlotCache(defaultReadMode, optimisticReads,
            durationBetweenCacheRefresh, maxAwaitCacheRefresh, allDiscoveryHostPorts, masterPools,
            masterSlots, slavePools, slaveSlots, masterPoolFactory, slavePoolFactory,
            jedisAskDiscoveryFactory, lbFactory);
      } catch (final JedisConnectionException e) {
        // try next discoveryNode...
      }
    }

    if (optimisticReads) {
      return new OptimisticJedisClusterSlotCache(defaultReadMode, durationBetweenCacheRefresh,
          maxAwaitCacheRefresh, allDiscoveryHostPorts, masterPools, masterSlots, slavePools,
          slaveSlots, masterPoolFactory, slavePoolFactory, jedisAskDiscoveryFactory, lbFactory);
    }

    return new JedisClusterSlotCache(defaultReadMode, optimisticReads, durationBetweenCacheRefresh,
        maxAwaitCacheRefresh, allDiscoveryHostPorts, masterPools, masterSlots, slavePools,
        slaveSlots, masterPoolFactory, slavePoolFactory, jedisAskDiscoveryFactory, lbFactory);
  }

  void discoverClusterSlots() {

    for (final HostPort discoveryHostPort : getDiscoveryHostPorts()) {

      try (final Jedis jedis = jedisAskDiscoveryFactory.apply(discoveryHostPort)) {

        discoverClusterSlots(jedis);
        return;
      } catch (final JedisConnectionException e) {
        // try next discovery node...
      }
    }
  }

  @SuppressWarnings("unchecked")
  void discoverClusterSlots(final Jedis jedis) {

    long dedupeDiscovery;
    long writeStamp;

    try {
      if (maxAwaitCacheRefreshNanos == 0) {

        dedupeDiscovery = refreshStamp;
        writeStamp = lock.writeLock();
      } else {
        dedupeDiscovery = refreshStamp;
        writeStamp = lock.tryWriteLock(maxAwaitCacheRefreshNanos, TimeUnit.NANOSECONDS);
      }
    } catch (final InterruptedException ie) {
      // allow dirty retry.
      return;
    }

    try {

      if (dedupeDiscovery != refreshStamp) {
        return;
      }

      // otherwise allow dirty reads
      if (!optimisticReads && maxAwaitCacheRefreshNanos == 0) {
        Arrays.fill(masterSlots, null);
        Arrays.fill(slaveSlots, null);
      }

      final Set<ClusterNode> staleMasterPools = new HashSet<>(masterPools.keySet());
      final Set<ClusterNode> staleSlavePools = new HashSet<>(slavePools.keySet());

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
            final ClusterNode masterNode = ClusterNode.create((List<Object>) slotInfo.get(2));
            discoveryHostPorts.add(masterNode.getHostPort());

            final ObjectPool<Jedis> masterPool = masterPoolFactory.apply(masterNode);
            masterPools.put(masterNode, masterPool);

            Arrays.fill(masterSlots, slotBegin, slotEnd, masterPool);
            break;
          case SLAVES:
          default:
            break;
        }

        final int slotInfoSize = slotInfo.size();
        if (slotInfoSize < 4) {
          continue;
        }

        final ObjectPool<Jedis>[] slotSlavePools =
            defaultReadMode == ReadMode.MASTER ? null : new ObjectPool[slotInfoSize - 3];

        for (int i = 3, poolIndex = 0; i < slotInfoSize; i++) {

          final ClusterNode slaveNode = ClusterNode.create((List<Object>) slotInfo.get(i));
          discoveryHostPorts.add(slaveNode.getHostPort());

          switch (defaultReadMode) {
            case SLAVES:
            case MIXED:
            case MIXED_SLAVES:
              staleSlavePools.remove(slaveNode);
              slotSlavePools[poolIndex++] = slavePools.computeIfAbsent(slaveNode, slavePoolFactory);
              break;
            case MASTER:
            default:
              break;
          }
        }

        if (defaultReadMode != ReadMode.MASTER) {

          final LoadBalancedPools lbPools = lbFactory.apply(slotSlavePools);
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
    } finally {
      try {
        refreshStamp = System.currentTimeMillis();
      } finally {
        lock.unlockWrite(writeStamp);
      }
    }
  }

  static HostPort createHostPort(final Jedis jedis) {

    final Client client = jedis.getClient();

    return HostPort.create(client.getHost(), client.getPort());
  }

  ObjectPool<Jedis> getAskPool(final ClusterNode askNode) {

    long readStamp = lock.tryOptimisticRead();

    ObjectPool<Jedis> pool = getAskPoolGuarded(askNode);

    if (!lock.validate(readStamp)) {

      try {
        readStamp = maxAwaitCacheRefreshNanos == 0 ? lock.readLock()
            : lock.tryReadLock(maxAwaitCacheRefreshNanos, TimeUnit.NANOSECONDS);
      } catch (final InterruptedException ie) {
        // allow dirty read.
        readStamp = 0;
      }

      try {
        pool = getAskPoolGuarded(askNode);
      } finally {
        if (readStamp > 0) {
          lock.unlockRead(readStamp);
        }
      }
    }

    return pool == null ? new SingletonPool(jedisAskDiscoveryFactory.apply(askNode.getHostPort()))
        : pool;
  }

  protected ObjectPool<Jedis> getAskPoolGuarded(final ClusterNode askNode) {

    switch (defaultReadMode) {
      case MASTER:
        return masterPools.get(askNode);
      case MIXED:
      case MIXED_SLAVES:
        ObjectPool<Jedis> pool = masterPools.get(askNode);

        if (pool == null) {
          pool = slavePools.get(askNode);
        }

        return pool;
      case SLAVES:
        return slavePools.get(askNode);
      default:
        return null;
    }
  }

  ObjectPool<Jedis> getSlotPool(final ReadMode readMode, final int slot) {

    switch (defaultReadMode) {
      case MASTER:
      case SLAVES:
        return getSlotPoolModeChecked(defaultReadMode, slot);
      case MIXED:
      case MIXED_SLAVES:
        return getSlotPoolModeChecked(readMode, slot);
      default:
        return null;
    }
  }

  protected ObjectPool<Jedis> getSlotPoolModeChecked(final ReadMode readMode, final int slot) {

    long readStamp = lock.tryOptimisticRead();

    final ObjectPool<Jedis> pool = getLoadBalancedPool(readMode, slot);

    if (lock.validate(readStamp)) {
      return pool;
    }

    try {
      readStamp = maxAwaitCacheRefreshNanos == 0 ? lock.readLock()
          : lock.tryReadLock(maxAwaitCacheRefreshNanos, TimeUnit.NANOSECONDS);
    } catch (final InterruptedException ie) {
      // allow dirty read.
      readStamp = 0;
    }

    try {
      return getLoadBalancedPool(readMode, slot);
    } finally {
      if (readStamp > 0) {
        lock.unlockRead(readStamp);
      }
    }
  }

  protected ObjectPool<Jedis> getLoadBalancedPool(final ReadMode readMode, final int slot) {

    switch (readMode) {
      case MASTER:
        return masterSlots[slot];
      case MIXED:
      case MIXED_SLAVES:
        LoadBalancedPools lbSlaves = slaveSlots[slot];
        if (lbSlaves == null) {
          return masterSlots[slot];
        }

        final ObjectPool<Jedis> slavePool = lbSlaves.next(readMode);

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

  List<ObjectPool<Jedis>> getPools(final ReadMode readMode) {

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

  private List<ObjectPool<Jedis>> getPoolsModeChecked(final ReadMode readMode) {

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

  List<ObjectPool<Jedis>> getMasterPools() {

    long readStamp = lock.tryOptimisticRead();

    final List<ObjectPool<Jedis>> pools = new ArrayList<>(masterPools.values());

    if (lock.validate(readStamp)) {
      return pools;
    }

    pools.clear();

    try {
      readStamp = maxAwaitCacheRefreshNanos == 0 ? lock.readLock()
          : lock.tryReadLock(maxAwaitCacheRefreshNanos, TimeUnit.NANOSECONDS);
    } catch (final InterruptedException ie) {
      // allow dirty read.
      readStamp = 0;
    }

    try {
      pools.addAll(masterPools.values());
      return pools;
    } finally {
      if (readStamp > 0) {
        lock.unlockRead(readStamp);
      }
    }
  }

  List<ObjectPool<Jedis>> getSlavePools() {

    long readStamp = lock.tryOptimisticRead();

    final List<ObjectPool<Jedis>> pools = new ArrayList<>(slavePools.values());

    if (lock.validate(readStamp)) {
      return pools;
    }

    pools.clear();

    try {
      readStamp = maxAwaitCacheRefreshNanos == 0 ? lock.readLock()
          : lock.tryReadLock(maxAwaitCacheRefreshNanos, TimeUnit.NANOSECONDS);
    } catch (final InterruptedException ie) {
      // allow dirty read.
      readStamp = 0;
    }

    try {
      pools.addAll(slavePools.values());
      return pools;
    } finally {
      if (readStamp > 0) {
        lock.unlockRead(readStamp);
      }
    }
  }

  List<ObjectPool<Jedis>> getAllPools() {

    long readStamp = lock.tryOptimisticRead();

    final List<ObjectPool<Jedis>> allPools =
        new ArrayList<>(masterPools.size() + slavePools.size());
    allPools.addAll(masterPools.values());
    allPools.addAll(slavePools.values());

    if (lock.validate(readStamp)) {
      return allPools;
    }

    allPools.clear();

    try {
      readStamp = maxAwaitCacheRefreshNanos == 0 ? lock.readLock()
          : lock.tryReadLock(maxAwaitCacheRefreshNanos, TimeUnit.NANOSECONDS);
    } catch (final InterruptedException ie) {
      // allow dirty read.
      readStamp = 0;
    }

    try {
      allPools.addAll(masterPools.values());
      allPools.addAll(slavePools.values());
      return allPools;
    } finally {
      if (readStamp > 0) {
        lock.unlockRead(readStamp);
      }
    }
  }

  ObjectPool<Jedis> getMasterPoolIfPresent(final ClusterNode node) {

    long readStamp = lock.tryOptimisticRead();

    final ObjectPool<Jedis> pool = masterPools.get(node);

    if (lock.validate(readStamp)) {
      return pool;
    }

    try {
      readStamp = maxAwaitCacheRefreshNanos == 0 ? lock.readLock()
          : lock.tryReadLock(maxAwaitCacheRefreshNanos, TimeUnit.NANOSECONDS);
    } catch (final InterruptedException ie) {
      // allow dirty read.
      readStamp = 0;
    }

    try {
      return masterPools.get(node);
    } finally {
      if (readStamp > 0) {
        lock.unlockRead(readStamp);
      }
    }
  }

  ObjectPool<Jedis> getSlavePoolIfPresent(final ClusterNode node) {

    long readStamp = lock.tryOptimisticRead();

    final ObjectPool<Jedis> pool = slavePools.get(node);

    if (lock.validate(readStamp)) {
      return pool;
    }

    try {
      readStamp = maxAwaitCacheRefreshNanos == 0 ? lock.readLock()
          : lock.tryReadLock(maxAwaitCacheRefreshNanos, TimeUnit.NANOSECONDS);
    } catch (final InterruptedException ie) {
      // allow dirty read.
      readStamp = 0;
    }

    try {
      return slavePools.get(node);
    } finally {
      if (readStamp > 0) {
        lock.unlockRead(readStamp);
      }
    }
  }

  ObjectPool<Jedis> getPoolIfPresent(final ClusterNode node) {

    long readStamp = lock.tryOptimisticRead();

    ObjectPool<Jedis> pool = masterPools.get(node);
    if (pool == null) {
      pool = slavePools.get(node);
    }

    if (lock.validate(readStamp)) {
      return pool;
    }

    try {
      readStamp = maxAwaitCacheRefreshNanos == 0 ? lock.readLock()
          : lock.tryReadLock(maxAwaitCacheRefreshNanos, TimeUnit.NANOSECONDS);
    } catch (final InterruptedException ie) {
      // allow dirty read.
      readStamp = 0;
    }

    try {
      pool = masterPools.get(node);
      if (pool == null) {
        pool = slavePools.get(node);
      }
      return pool;
    } finally {
      if (readStamp > 0) {
        lock.unlockRead(readStamp);
      }
    }
  }

  Set<HostPort> getDiscoveryHostPorts() {

    return discoveryHostPorts;
  }

  @Override
  public void close() {

    long writeStamp;
    try {
      writeStamp = lock.tryWriteLock(Math.min(1_000_000_000, maxAwaitCacheRefreshNanos),
          TimeUnit.NANOSECONDS);
    } catch (final InterruptedException e1) {
      // allow dirty write.
      writeStamp = 0;
    }

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
      if (writeStamp > 0) {
        lock.unlockWrite(writeStamp);
      }
    }
  }
}
