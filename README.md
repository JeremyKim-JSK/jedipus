##Jedipus [![Build Status](https://travis-ci.org/jamespedwards42/jedipus.svg?branch=master)](https://travis-ci.org/jamespedwards42/jedipus) [![JCenter](https://api.bintray.com/packages/jamespedwards42/libs/jedipus/images/download.svg) ](https://bintray.com/jamespedwards42/libs/jedipus/_latestVersion) [![License](http://img.shields.io/badge/license-Apache--2-blue.svg?style=flat) ](http://www.apache.org/licenses/LICENSE-2.0) [![Gitter Chat](https://badges.gitter.im/jamespedwards42/jedipus.svg)](https://gitter.im/jamespedwards42/jedipus?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

>Jedipus is a Redis Cluster Java client that manages JedisPool's.

######Features
* Execute Jedis Consumer and Function Java 8 Lambas against a Redis Cluster.
* Use known slot ints for O(1) direct primitive array access to a corresponding JedisPool.
* Locking is only applied to threads that are accessing slots that are MOVING or for which a client connection cannot be established triggering a slot cache refresh.
* Re-uses the work already done on Jedis clients to support pipelining and transactions.  Remember that all keys must share the same hash slot, instead of validating this, Jedipus trusts the user in order to avoid unnecessary overhead.
* Minimal dependency tree (Jedipus -> Jedis -> org.apache.commons:commons-pool2).
* Utilities to manage and execute Lua scripts.
* Optional user supplied master and slave HostAndPort -> JedisPool factories.  Useful for client side ip/port mapping or dynamic pool sizes.
* Load balance read-only requests across pools.  Optional user supplied slave JedisPool[] -> LoadBalancedPools factories.  By default, a round robin strategy is used.
* Configurable retry delay per HostAndPort for JedisConnectionException's.

######Read Modes
>Read modes control how pools to master and slave nodes are managed.

* MASTER: Only pools to master nodes are maintained.  
* SLAVES: Only pools to slave nodes are maintained. Calls are load balanced across slave pools.
* MIXED_SLAVES: Pools are managed for both masters and slave nodes.  Calls are only load balanced across slave pools. Individual calls can be overridden with `ReadMode.MASTER` or `ReadMode.MIXED`.  When no slave pools are available the master pool is used.
* MIXED: Pools are managed for both masters and slave nodes.  Calls are load balanced across both master and slave pools. Individual calls can be overridden with `ReadMode.MASTER` or `ReadMode.SLAVES`.  When overriding with `ReadMode.SLAVES` and no slave pools are available the master pool is used.

#####Basic Usage Example
```java
final Collection<HostAndPort> discoveryHostPorts =
        Collections.singleton(new HostAndPort("127.0.0.1", 7000));

try (final JedisClusterExecutor jce = JedisClusterExecutor.startBuilding(discoveryHostPorts)
       .withReadMode(ReadMode.MIXED_SLAVES).withInitReadOnly(true).create()) {

   // Ping-Pong all masters.
   jce.acceptAllMasters(master -> System.out.format("MASTER@%s:%d %s%n",
       master.getClient().getHost(), master.getClient().getPort(), master.ping()));

   // Ping-Pong all slaves.
   jce.acceptAllSlaves(slave -> System.out.format("SLAVE@%s:%d %s%n",
       slave.getClient().getHost(), slave.getClient().getPort(), slave.ping()));

   // Hash tagged pipelined transaction.
   final String hashTag = RCUtils.createNameSpacedHashTag("HT");
   final int slot = JedisClusterCRC16.getSlot(hashTag);

   final String hashTaggedKey = hashTag + "key";
   final String fooKey = hashTag + "foo";

   final List<Response<?>> results = new ArrayList<>(2);

   jce.acceptPipelinedTransaction(ReadMode.MASTER, slot, pipeline -> {

     pipeline.set(hashTaggedKey, "value");
     pipeline.zadd(fooKey, -1, "barowitch");
     pipeline.zadd(fooKey, .37, "barinsky");
     pipeline.zadd(fooKey, 42, "barikoviev");

     results.add(pipeline.get(hashTaggedKey));
     results.add(pipeline.zrangeWithScores(fooKey, 0, -1));
   });

   // '{HT}:key': value
   System.out.format("%n'%s': %s%n", hashTaggedKey, results.get(0).get());

   @SuppressWarnings("unchecked")
   final Set<Tuple> zrangeResult = (Set<Tuple>) results.get(1).get();
   final String values = zrangeResult.stream()
       .map(tuple -> String.format("%s (%s)", tuple.getElement(), tuple.getScore()))
       .collect(Collectors.joining(", "));

   // '{HT}:foo': [barowitch (-1.0), barinsky (0.37), barikoviev (42.0)]
   System.out.format("%n'%s': [%s]%n", fooKey, values);

   // Read from load balanced slave.
   final String roResult =
       jce.applyJedis(ReadMode.SLAVES, slot, jedis -> jedis.get(hashTaggedKey));
   System.out.format("%n'%s': %s%n", hashTaggedKey, roResult);

   // cleanup
   final long numRemoved =
       jce.applyJedis(ReadMode.MASTER, slot, jedis -> jedis.del(hashTaggedKey, fooKey));
   System.out.format("%nRemoved %d keys.%n", numRemoved);
}
```

#####Redis Lock Lua Example

```java
public final class RedisLock {

  private RedisLock() {}

  private static final LuaScriptData TRY_ACQUIRE_LOCK =
      LuaScriptData.fromResourcePath("/TRY_ACQUIRE_LOCK.lua");

  private static final LuaScriptData TRY_RELEASE_LOCK =
      LuaScriptData.fromResourcePath("/TRY_RELEASE_LOCK.lua");

  @SuppressWarnings("unchecked")
  public static void main(final String[] args) {

    final Collection<HostAndPort> discoveryHostPorts =
        Collections.singleton(new HostAndPort("127.0.0.1", 7000));

    try (final JedisClusterExecutor jce = JedisClusterExecutor
            .startBuilding(discoveryHostPorts).create()) {

      LuaScript.loadMissingScripts(jce, TRY_ACQUIRE_LOCK, TRY_RELEASE_LOCK);

      final byte[] lockName = RESP.toBytes("mylock");
      final byte[] ownerId = RESP.toBytes("myOwnerId");
      final byte[] pexpire = RESP.toBytes(3000);

      final List<Object> lockOwners = (List<Object>) TRY_ACQUIRE_LOCK.eval(jce, 1,
          lockName, ownerId, pexpire);

      // final byte[] previousOwner = (byte[]) lockOwners.get(0);
      final byte[] currentOwner = (byte[]) lockOwners.get(1);
      final long pttl = (long) lockOwners.get(2);

      // 'myOwnerId' has lock 'mylock' for 3000ms.
      System.out.format("'%s' has lock '%s' for %dms.%n", RESP.toString(currentOwner),
          RESP.toString(lockName), pttl);

      final long released = (long) TRY_RELEASE_LOCK.eval(jce, 1, lockName, ownerId);

      if (released == 1) {
        // Lock was released by 'myOwnerId'.
        System.out.format("Lock was released by '%s'.%n", RESP.toString(ownerId));
      } else {
        System.out.format("Lock was no longer owned by '%s'.%n", RESP.toString(ownerId));
      }
    }
  }
```

**src/main/resoures/TRY_ACQUIRE_LOCK.lua**
```lua
-- Returns the previous owner, the current owner and the pttl for the lock.
-- Returns either {null, lockOwner, pexpire}, {owner, owner, pexpire} or {owner, owner, pttl}.
-- The previous owner is null if 'lockOwner' newly acquired the lock. Otherwise, the previous
--   owner will be same value as the current owner. If the current owner is equal to the supplied
--   'lockOwner' argument then the ownership claim will remain active for 'pexpire' milliseconds.

local lockName = KEYS[1];
local lockOwner = ARGV[1];

local owner = redis.call('get', lockName);

if not owner or owner == lockOwner then

   local px = tonumber(ARGV[2]);

   redis.call('set', lockName, lockOwner, 'PX', px);

   return {owner, lockOwner, px};
end

return {owner, owner, redis.call('pttl', lockName)};
```

**src/main/resoures/TRY_RELEASE_LOCK.lua**
```lua
local lockName = KEYS[1];
local lockOwner = ARGV[1];

if redis.call('get', lockName) == lockOwner then
   return redis.call('del', lockName);
end

return -1;
```

#####Dependency Management
######Gradle
```groovy
repositories {
   jcenter()
}

dependencies {
   // Optional
   // compile 'org.apache.commons:commons-pool2:+'
   compile 'redis.clients:jedis:+'
   compile 'com.fabahaba:jedipus:+'
}
```
