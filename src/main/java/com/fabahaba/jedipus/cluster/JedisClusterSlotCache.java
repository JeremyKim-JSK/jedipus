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
import com.fabahaba.jedipus.IJedis;
import com.fabahaba.jedipus.RESP;
import com.fabahaba.jedipus.cluster.JedisClusterExecutor.ReadMode;

import redis.clients.jedis.BinaryJedisCluster;
import redis.clients.jedis.exceptions.JedisConnectionException;

class JedisClusterSlotCache implements AutoCloseable {

  private final ReadMode defaultReadMode;

  private final Map<HostPort, ClusterNode> discoveryNodes;

  protected final Map<ClusterNode, ObjectPool<IJedis>> masterPools;
  private final ObjectPool<IJedis>[] masterSlots;

  private final Function<ObjectPool<IJedis>[], LoadBalancedPools> lbFactory;
  protected final Map<ClusterNode, ObjectPool<IJedis>> slavePools;
  private final LoadBalancedPools[] slaveSlots;

  private final boolean optimisticReads;
  private final long maxAwaitCacheRefreshNanos;
  private final StampedLock lock;
  private final long millisBetweenSlotCacheRefresh;
  private volatile long refreshStamp = 0;

  private final Function<ClusterNode, ObjectPool<IJedis>> masterPoolFactory;
  private final Function<ClusterNode, ObjectPool<IJedis>> slavePoolFactory;
  protected final Function<ClusterNode, IJedis> nodeUnknownFactory;

  JedisClusterSlotCache(final ReadMode defaultReadMode, final boolean optimisticReads,
      final Duration durationBetweenCacheRefresh, final Duration maxAwaitCacheRefresh,
      final Map<HostPort, ClusterNode> discoveryNodes,
      final Map<ClusterNode, ObjectPool<IJedis>> masterPools,
      final ObjectPool<IJedis>[] masterSlots, final Map<ClusterNode, ObjectPool<IJedis>> slavePools,
      final LoadBalancedPools[] slaveSlots,
      final Function<ClusterNode, ObjectPool<IJedis>> masterPoolFactory,
      final Function<ClusterNode, ObjectPool<IJedis>> slavePoolFactory,
      final Function<ClusterNode, IJedis> nodeUnknownFactory,
      final Function<ObjectPool<IJedis>[], LoadBalancedPools> lbFactory) {

    this.refreshStamp = System.currentTimeMillis();

    this.defaultReadMode = defaultReadMode;
    this.discoveryNodes = discoveryNodes;

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
    this.nodeUnknownFactory = nodeUnknownFactory;
    this.lbFactory = lbFactory;
  }

  ReadMode getDefaultReadMode() {

    return defaultReadMode;
  }

  Function<ClusterNode, IJedis> getNodeUnknownFactory() {

    return nodeUnknownFactory;
  }

  @SuppressWarnings("unchecked")
  static JedisClusterSlotCache create(final ReadMode defaultReadMode, final boolean optimisticReads,
      final Duration durationBetweenCacheRefresh, final Duration maxAwaitCacheRefresh,
      final Collection<ClusterNode> discoveryNodes,
      final Function<ClusterNode, ObjectPool<IJedis>> masterPoolFactory,
      final Function<ClusterNode, ObjectPool<IJedis>> slavePoolFactory,
      final Function<ClusterNode, IJedis> nodeUnknownFactory,
      final Function<ObjectPool<IJedis>[], LoadBalancedPools> lbFactory) {

    final Map<ClusterNode, ObjectPool<IJedis>> masterPools =
        defaultReadMode == ReadMode.SLAVES ? Collections.emptyMap() : new ConcurrentHashMap<>();
    final ObjectPool<IJedis>[] masterSlots = defaultReadMode == ReadMode.SLAVES ? new ObjectPool[0]
        : new ObjectPool[BinaryJedisCluster.HASHSLOTS];

    final Map<ClusterNode, ObjectPool<IJedis>> slavePools =
        defaultReadMode == ReadMode.MASTER ? Collections.emptyMap() : new ConcurrentHashMap<>();
    final LoadBalancedPools[] slaveSlots = defaultReadMode == ReadMode.MASTER
        ? new LoadBalancedPools[0] : new LoadBalancedPools[BinaryJedisCluster.HASHSLOTS];

    return create(defaultReadMode, optimisticReads, durationBetweenCacheRefresh,
        maxAwaitCacheRefresh, discoveryNodes, masterPoolFactory, slavePoolFactory,
        nodeUnknownFactory, lbFactory, masterPools, masterSlots, slavePools, slaveSlots);
  }

  @SuppressWarnings("unchecked")
  private static JedisClusterSlotCache create(final ReadMode defaultReadMode,
      final boolean optimisticReads, final Duration durationBetweenCacheRefresh,
      final Duration maxAwaitCacheRefresh, final Collection<ClusterNode> discoveryNodes,
      final Function<ClusterNode, ObjectPool<IJedis>> masterPoolFactory,
      final Function<ClusterNode, ObjectPool<IJedis>> slavePoolFactory,
      final Function<ClusterNode, IJedis> nodeUnknownFactory,
      final Function<ObjectPool<IJedis>[], LoadBalancedPools> lbFactory,
      final Map<ClusterNode, ObjectPool<IJedis>> masterPools,
      final ObjectPool<IJedis>[] masterSlots, final Map<ClusterNode, ObjectPool<IJedis>> slavePools,
      final LoadBalancedPools[] slaveSlots) {

    final Map<HostPort, ClusterNode> allDiscoveryNodes =
        new ConcurrentHashMap<>(discoveryNodes.size());
    discoveryNodes.forEach(node -> allDiscoveryNodes.put(node.getHostPort(), node));

    for (final ClusterNode discoveryHostPort : discoveryNodes) {

      try (final IJedis jedis = nodeUnknownFactory.apply(discoveryHostPort)) {

        final List<Object> slots = jedis.clusterSlots();

        for (final Object slotInfoObj : slots) {

          final List<Object> slotInfo = (List<Object>) slotInfoObj;

          final int slotBegin = RESP.longToInt(slotInfo.get(0));
          final int slotEnd = RESP.longToInt(slotInfo.get(1)) + 1;

          switch (defaultReadMode) {
            case MIXED_SLAVES:
            case MIXED:
            case MASTER:
              final ClusterNode masterNode = ClusterNode.create((List<Object>) slotInfo.get(2));
              allDiscoveryNodes.put(masterNode.getHostPort(), masterNode);

              final ObjectPool<IJedis> masterPool = masterPoolFactory.apply(masterNode);
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

          final ObjectPool<IJedis>[] slotSlavePools =
              defaultReadMode == ReadMode.MASTER ? null : new ObjectPool[slotInfoSize - 3];

          for (int i = 3, poolIndex = 0; i < slotInfoSize; i++) {

            final ClusterNode slaveNode = ClusterNode.create((List<Object>) slotInfo.get(i));
            allDiscoveryNodes.put(slaveNode.getHostPort(), slaveNode);

            switch (defaultReadMode) {
              case SLAVES:
              case MIXED:
              case MIXED_SLAVES:
                final ObjectPool<IJedis> slavePool = slavePoolFactory.apply(slaveNode);
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
              maxAwaitCacheRefresh, allDiscoveryNodes, masterPools, masterSlots, slavePools,
              slaveSlots, masterPoolFactory, slavePoolFactory, nodeUnknownFactory, lbFactory);
        }

        return new JedisClusterSlotCache(defaultReadMode, optimisticReads,
            durationBetweenCacheRefresh, maxAwaitCacheRefresh, allDiscoveryNodes, masterPools,
            masterSlots, slavePools, slaveSlots, masterPoolFactory, slavePoolFactory,
            nodeUnknownFactory, lbFactory);
      } catch (final JedisConnectionException e) {
        // try next discoveryNode...
      }
    }

    if (optimisticReads) {
      return new OptimisticJedisClusterSlotCache(defaultReadMode, durationBetweenCacheRefresh,
          maxAwaitCacheRefresh, allDiscoveryNodes, masterPools, masterSlots, slavePools, slaveSlots,
          masterPoolFactory, slavePoolFactory, nodeUnknownFactory, lbFactory);
    }

    return new JedisClusterSlotCache(defaultReadMode, optimisticReads, durationBetweenCacheRefresh,
        maxAwaitCacheRefresh, allDiscoveryNodes, masterPools, masterSlots, slavePools, slaveSlots,
        masterPoolFactory, slavePoolFactory, nodeUnknownFactory, lbFactory);
  }

  void discoverClusterSlots() {

    for (final ClusterNode discoveryNode : getDiscoveryNodes()) {

      try (final IJedis jedis = nodeUnknownFactory.apply(discoveryNode)) {

        discoverClusterSlots(jedis);
        return;
      } catch (final JedisConnectionException e) {
        // try next discovery node...
      }
    }
  }

  @SuppressWarnings("unchecked")
  void discoverClusterSlots(final IJedis jedis) {

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
        final int slotEnd = RESP.longToInt(slotInfo.get(1)) + 1;

        switch (defaultReadMode) {
          case MIXED_SLAVES:
          case MIXED:
          case MASTER:
            final ClusterNode masterNode = ClusterNode.create((List<Object>) slotInfo.get(2));
            final ClusterNode known =
                discoveryNodes.putIfAbsent(masterNode.getHostPort(), masterNode);
            if (known != null) {
              known.updateId(masterNode.getId());
            }

            final ObjectPool<IJedis> masterPool = masterPoolFactory.apply(masterNode);
            masterPools.put(masterNode, masterPool);
            staleMasterPools.remove(masterNode);

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

        final ObjectPool<IJedis>[] slotSlavePools =
            defaultReadMode == ReadMode.MASTER ? null : new ObjectPool[slotInfoSize - 3];

        for (int i = 3, poolIndex = 0; i < slotInfoSize; i++) {

          final ClusterNode slaveNode = ClusterNode.create((List<Object>) slotInfo.get(i));
          final ClusterNode known = discoveryNodes.putIfAbsent(slaveNode.getHostPort(), slaveNode);
          if (known != null) {
            known.updateId(slaveNode.getId());
          }

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

  ObjectPool<IJedis> getAskPool(final ClusterNode askNode) {

    long readStamp = lock.tryOptimisticRead();

    ObjectPool<IJedis> pool = getAskPoolGuarded(askNode);

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

    return pool == null ? new SingletonPool(nodeUnknownFactory.apply(askNode)) : pool;
  }

  protected ObjectPool<IJedis> getAskPoolGuarded(final ClusterNode askNode) {

    switch (defaultReadMode) {
      case MASTER:
        return masterPools.get(askNode);
      case MIXED:
      case MIXED_SLAVES:
        ObjectPool<IJedis> pool = masterPools.get(askNode);

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

  ObjectPool<IJedis> getSlotPool(final ReadMode readMode, final int slot) {

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

  protected ObjectPool<IJedis> getSlotPoolModeChecked(final ReadMode readMode, final int slot) {

    long readStamp = lock.tryOptimisticRead();

    final ObjectPool<IJedis> pool = getLoadBalancedPool(readMode, slot);

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

  protected ObjectPool<IJedis> getLoadBalancedPool(final ReadMode readMode, final int slot) {

    switch (readMode) {
      case MASTER:
        return masterSlots[slot];
      case MIXED:
      case MIXED_SLAVES:
        LoadBalancedPools lbSlaves = slaveSlots[slot];
        if (lbSlaves == null) {
          return masterSlots[slot];
        }

        final ObjectPool<IJedis> slavePool = lbSlaves.next(readMode);

        return slavePool == null ? masterSlots[slot] : slavePool;
      case SLAVES:
        lbSlaves = slaveSlots[slot];
        if (lbSlaves == null) {
          return masterSlots.length == 0 ? null : masterSlots[slot];
        }

        return lbSlaves.next(readMode);
      default:
        return null;
    }
  }

  List<ObjectPool<IJedis>> getPools(final ReadMode readMode) {

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

  private List<ObjectPool<IJedis>> getPoolsModeChecked(final ReadMode readMode) {

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

  List<ObjectPool<IJedis>> getMasterPools() {

    long readStamp = lock.tryOptimisticRead();

    final List<ObjectPool<IJedis>> pools = new ArrayList<>(masterPools.values());

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

  List<ObjectPool<IJedis>> getSlavePools() {

    long readStamp = lock.tryOptimisticRead();

    final List<ObjectPool<IJedis>> pools = new ArrayList<>(slavePools.values());

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

  List<ObjectPool<IJedis>> getAllPools() {

    long readStamp = lock.tryOptimisticRead();

    final List<ObjectPool<IJedis>> allPools =
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

  ObjectPool<IJedis> getMasterPoolIfPresent(final ClusterNode node) {

    long readStamp = lock.tryOptimisticRead();

    final ObjectPool<IJedis> pool = masterPools.get(node);

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

  ObjectPool<IJedis> getSlavePoolIfPresent(final ClusterNode node) {

    long readStamp = lock.tryOptimisticRead();

    final ObjectPool<IJedis> pool = slavePools.get(node);

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

  ObjectPool<IJedis> getPoolIfPresent(final ClusterNode node) {

    long readStamp = lock.tryOptimisticRead();

    ObjectPool<IJedis> pool = masterPools.get(node);
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

  Collection<ClusterNode> getDiscoveryNodes() {

    return discoveryNodes.values();
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
      discoveryNodes.clear();

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
      Arrays.fill(masterSlots, null);

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
      Arrays.fill(slaveSlots, null);
    } finally {
      if (writeStamp > 0) {
        lock.unlockWrite(writeStamp);
      }
    }
  }
}
