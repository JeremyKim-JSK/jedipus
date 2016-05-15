package com.fabahaba.jedipus.primitive;

import java.util.function.Function;

class AdaptedFutureLongArrayReply extends StatefulFutureReply<long[][]> {

  private final Function<long[][], long[][]> adapter;
  private long[][] reply;
  private long[][] adapted;

  AdaptedFutureLongArrayReply(final Function<long[][], long[][]> adapter) {

    this.adapter = adapter;
  }

  @Override
  public long[][] get() {

    checkReply();

    return adapted;
  }

  @Override
  protected void handleReply() {

    adapted = adapter.apply(reply);
  }

  @Override
  public AdaptedFutureLongArrayReply setReply(final PrimRedisConn conn) {
    this.reply = conn.getLongArrayArray();
    state = State.PENDING;
    return this;
  }
}
