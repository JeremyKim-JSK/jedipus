package com.fabahaba.jedipus;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.Stream;

import com.fabahaba.jedipus.cluster.JedisClusterExecutor;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;

public interface LuaScript {

  public String getLuaScript();

  public String getSha1();

  public ByteBuffer getSha1Bytes();

  default void loadIfMissing(final JedisExecutor jedisExecutor) {

    LuaScript.loadMissingScripts(jedisExecutor, this);
  }

  public Object eval(final JedisExecutor jedisExecutor, final int numRetries, final int keyCount,
      final byte[]... params);

  public Object eval(final Jedis jedis, final int numRetries, final int keyCount,
      final byte[]... params);

  public Object eval(final JedisExecutor jedisExecutor, final int numRetries,
      final List<byte[]> keys, final List<byte[]> args);

  public Object eval(final Jedis jedis, final int numRetries, final List<byte[]> keys,
      final List<byte[]> args);

  public Object eval(final Jedis jedis, final int keyCount, final byte[]... params);

  public Object eval(final Jedis jedis, final List<byte[]> keys, final List<byte[]> args);

  public static void loadMissingScripts(final JedisExecutor jedisExecutor,
      final LuaScript... luaScripts) {

    final byte[][] scriptSha1Bytes = Stream.of(luaScripts).map(LuaScript::getSha1Bytes)
        .map(ByteBuffer::array).toArray(byte[][]::new);

    jedisExecutor.acceptJedis(jedis -> loadIfNotExists(jedis, scriptSha1Bytes, luaScripts));
  }

  public static void loadIfNotExists(final Jedis jedis, final byte[][] scriptSha1Bytes,
      final LuaScript[] luaScripts) {

    final List<Long> existResults = jedis.scriptExists(scriptSha1Bytes);

    int index = 0;
    for (final long exists : existResults) {
      if (exists == 0) {
        jedis.scriptLoad(luaScripts[index].getLuaScript());
      }
      index++;
    }
  }

  public Object eval(final JedisClusterExecutor jedisExecutor, final int numRetries,
      final int keyCount, final byte[]... params);

  public Object eval(JedisCluster jedis, int numRetries, int keyCount, byte[]... params);

  public Object eval(final JedisClusterExecutor jedisExecutor, final int numRetries,
      final List<byte[]> keys, final List<byte[]> args);

  public Object eval(JedisCluster jedis, int numRetries, List<byte[]> keys, List<byte[]> args);

  public Object eval(final JedisCluster jedis, final int keyCount, final byte[]... params);

  public Object eval(final JedisCluster jedis, final List<byte[]> keys, final List<byte[]> args);

  public static void loadMissingScripts(final JedisClusterExecutor jedisExecutor,
      final LuaScript... luaScripts) {

    final byte[][] scriptSha1Bytes = Stream.of(luaScripts).map(LuaScript::getSha1Bytes)
        .map(ByteBuffer::array).toArray(byte[][]::new);

    jedisExecutor.acceptJedis(jedisCluster -> {

      jedisCluster.getClusterNodes().values().forEach(jedisPool -> {

        try (final Jedis jedis = jedisPool.getResource()) {

          if (jedis.info().contains("role:master")) {

            loadIfNotExists(jedis, scriptSha1Bytes, luaScripts);
          }
        }
      });
    });
  }
}