package com.fabahaba.jedipus.primitive;

import com.fabahaba.jedipus.client.RedisClient;

public abstract class BaseRedisSubscriber implements RedisSubscriber {

  private final RedisClient client;
  private long numSub = Long.MAX_VALUE;

  protected BaseRedisSubscriber(final RedisClient client) {
    this.client = client;
  }

  @Override
  public final void run() {
    for (; numSub > 0;) {
      client.consumePubSub(this);
    }
  }

  @Override
  public long getSubCount() {
    return numSub;
  }

  @Override
  public final void subscribe(final MsgConsumer msgConsumer, final String... channels) {
    client.subscribe(channels);
    registerConsumer(msgConsumer, channels);
    client.flush();
  }

  @Override
  public final void psubscribe(final MsgConsumer msgConsumer, final String... patterns) {
    client.psubscribe(patterns);
    registerPConsumer(msgConsumer, patterns);
    client.flush();
  }

  public abstract void registerConsumer(final MsgConsumer msgConsumer, final String... channels);

  public void registerPConsumer(final MsgConsumer msgConsumer, final String... patterns) {
    registerConsumer(msgConsumer, patterns);
  }

  @Override
  public final void unsubscribe(final String... channels) {
    client.unsubscribe(channels);
    client.flush();
  }

  @Override
  public final void punsubscribe(final String... patterns) {
    client.punsubscribe(patterns);
    client.flush();
  }

  protected abstract void onSubscribe(final String channel);

  @Override
  public final void onSubscribe(final String channel, final long numSubs) {
    this.numSub = numSubs;
    onSubscribe(channel);
  }

  public abstract void onUnsubscribe(final String channel);

  @Override
  public final void onUnsubscribe(final String channel, final long numSubs) {
    this.numSub = numSubs;
    onUnsubscribe(channel);
  }

  @Override
  public void close() {
    client.close();
  }
}
