package com.fabahaba.jedipus.cmds.pipeline;

import com.fabahaba.jedipus.client.FutureReply;
import com.fabahaba.jedipus.cmds.Cmd;
import com.fabahaba.jedipus.cmds.RESP;

public interface PipelineDirectCmds extends PipelineDirectPrimCmds {

  public <T> FutureReply<T> sendCmd(final Cmd<T> cmd);

  public <T> FutureReply<T> sendCmd(final Cmd<?> cmd, final Cmd<T> subCmd);

  public <T> FutureReply<T> sendCmd(final Cmd<?> cmd, final Cmd<T> subCmd, final byte[] arg);

  public <T> FutureReply<T> sendCmd(final Cmd<?> cmd, final Cmd<T> subCmd, final byte[]... args);

  public <T> FutureReply<T> sendCmd(final Cmd<T> cmd, final byte[] arg);

  public <T> FutureReply<T> sendCmd(final Cmd<T> cmd, final byte[]... args);

  default <T> FutureReply<T> sendCmd(final Cmd<?> cmd, final Cmd<T> subCmd, final String arg) {

    return sendCmd(cmd, subCmd, RESP.toBytes(arg));
  }

  public <T> FutureReply<T> sendCmd(final Cmd<?> cmd, final Cmd<T> subCmd, final String... args);

  default <T> FutureReply<T> sendCmd(final Cmd<T> cmd, final String arg) {

    return sendCmd(cmd, RESP.toBytes(arg));
  }

  public <T> FutureReply<T> sendCmd(final Cmd<T> cmd, final String... args);
}
