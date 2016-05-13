package com.fabahaba.jedipus.primitive;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;

import com.fabahaba.jedipus.FutureLongReply;
import com.fabahaba.jedipus.FutureReply;
import com.fabahaba.jedipus.RedisPipeline;
import com.fabahaba.jedipus.cluster.Node;
import com.fabahaba.jedipus.exceptions.RedisUnhandledException;

final class PrimRedisClient extends BaseRedisClient {

  // All lazy in case pipelines are not used.
  private Queue<StatefulFutureReply<?>> pipelinedReplies;
  private Queue<StatefulFutureReply<?>> multiReplies;
  private MultiReplyHandler multiReplyHandler;
  private PrimMultiReplyHandler primMultiReplyHandler;
  private PrimPipeline pipeline = null;

  PrimRedisClient(final Node node, final Function<Node, Node> hostPortMapper, final int connTimeout,
      final int soTimeout) {

    this(node, hostPortMapper, connTimeout, soTimeout, false, null, null, null);
  }

  PrimRedisClient(final Node node, final Function<Node, Node> hostPortMapper, final int connTimeout,
      final int soTimeout, final boolean ssl, final SSLSocketFactory sslSocketFactory,
      final SSLParameters sslParameters, final HostnameVerifier hostnameVerifier) {

    super(PrimRedisConn.create(node, hostPortMapper, connTimeout, soTimeout, ssl, sslSocketFactory,
        sslParameters, hostnameVerifier));
  }

  private Queue<StatefulFutureReply<?>> getMultiReplies() {

    if (multiReplies == null) {
      multiReplies = new ArrayDeque<>();
    }

    return multiReplies;
  }

  FutureReply<Object[]> queueMultiReplyHandler() {

    final DeserializedFutureReply<Object[]> futureMultiReply =
        new DeserializedFutureReply<>(multiReplyHandler);

    pipelinedReplies.add(futureMultiReply);
    multiReplyHandler.setExecReplyDependency(futureMultiReply);
    return futureMultiReply;
  }

  <T> FutureReply<T> queueFutureReply(final Function<Object, T> builder) {

    if (conn.isInMulti()) {
      return queueMultiPipelinedReply(builder);
    }

    return queuePipelinedReply(builder);
  }

  <T> FutureReply<T> queuePipelinedReply(final Function<Object, T> builder) {

    final StatefulFutureReply<T> futureReply = new DeserializedFutureReply<>(builder);
    pipelinedReplies.add(futureReply);
    return futureReply;
  }

  private <T> FutureReply<T> queueMultiPipelinedReply(final Function<Object, T> builder) {

    // handle QUEUED response
    pipelinedReplies.add(new DirectFutureReply<>());

    final StatefulFutureReply<T> futureReply = new DeserializedFutureReply<>(builder);
    if (multiReplyHandler == null) {
      multiReplyHandler = new MultiReplyHandler(getMultiReplies());
    }
    multiReplyHandler.queueFutureReply(futureReply);
    return futureReply;
  }

  FutureReply<long[]> queuePrimMultiReplyHandler() {

    final DeserializedFutureReply<long[]> futureMultiReply =
        new DeserializedFutureReply<>(primMultiReplyHandler);

    pipelinedReplies.add(futureMultiReply);
    primMultiReplyHandler.setExecReplyDependency(futureMultiReply);
    return futureMultiReply;
  }

  FutureLongReply queueFutureReply(final LongUnaryOperator adapter) {

    if (conn.isInMulti()) {
      return queueMultiPipelinedReply(adapter);
    }

    return queuePipelinedReply(adapter);
  }

  FutureLongReply queuePipelinedReply(final LongUnaryOperator adapter) {

    final StatefulFutureReply<Void> futureResponse = new AdaptedFutureLongReply(adapter);
    pipelinedReplies.add(futureResponse);
    return futureResponse;
  }

  private FutureLongReply queueMultiPipelinedReply(final LongUnaryOperator adapter) {

    // handle QUEUED response
    pipelinedReplies.add(new DirectFutureReply<>());

    final StatefulFutureReply<Void> futureResponse = new AdaptedFutureLongReply(adapter);
    if (primMultiReplyHandler == null) {
      primMultiReplyHandler = new PrimMultiReplyHandler(getMultiReplies());
    }
    primMultiReplyHandler.queueFutureReply(futureResponse);
    return futureResponse;
  }

  void sync() {

    if (conn.isInMulti()) {
      throw new RedisUnhandledException(null, "EXEC your MULTI before calling SYNC.");
    }

    conn.flush();

    for (final Queue<StatefulFutureReply<?>> responses = pipelinedReplies;;) {

      final StatefulFutureReply<?> response = responses.poll();

      if (response == null) {
        return;
      }

      try {
        response.setReply(conn);
      } catch (final RedisUnhandledException re) {
        response.setException(re);
      }
    }
  }

  void closePipeline() {

    pipelinedReplies.clear();

    if (multiReplies != null) {
      multiReplies.clear();
    }

    if (conn.isInMulti()) {
      conn.discard();
      conn.getReply(MultiCmds.DISCARD.raw());
    }
  }

  @Override
  public void resetState() {

    if (pipeline != null) {
      pipeline.close();
    }

    conn.resetState();

    pipeline = null;
  }

  @Override
  public RedisPipeline createPipeline() {

    if (pipeline != null) {
      throw new RedisUnhandledException(getNode(), "Pipeline has already been created.");
    }

    if (pipelinedReplies == null) {
      pipelinedReplies = new ArrayDeque<>();
    }

    this.pipeline = new PrimPipeline(this);

    return pipeline;
  }

  @Override
  public RedisPipeline createOrUseExistingPipeline() {

    if (pipeline != null) {
      return pipeline;
    }

    return createPipeline();
  }
}
