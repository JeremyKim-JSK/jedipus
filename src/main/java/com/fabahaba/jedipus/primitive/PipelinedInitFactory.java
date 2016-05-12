package com.fabahaba.jedipus.primitive;

import java.util.function.Function;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;

import com.fabahaba.jedipus.RedisClient;
import com.fabahaba.jedipus.RedisPipeline;
import com.fabahaba.jedipus.cluster.Node;
import com.fabahaba.jedipus.cmds.ClusterCmds;
import com.fabahaba.jedipus.cmds.Cmds;

class PipelinedInitFactory extends RedisClientFactory {

  PipelinedInitFactory(final Node node, final Function<Node, Node> hostPortMapper,
      final int connTimeout, final int soTimeout, final String pass, final String clientName,
      final boolean initReadOnly, final boolean ssl, final SSLSocketFactory sslSocketFactory,
      final SSLParameters sslParameters, final HostnameVerifier hostnameVerifier) {

    super(node, hostPortMapper, connTimeout, soTimeout, pass, clientName, initReadOnly, ssl,
        sslSocketFactory, sslParameters, hostnameVerifier);
  }

  @Override
  protected void initClient(final RedisClient client) {

    final RedisPipeline pipeline = client.createPipeline();

    if (pass != null) {

      pipeline.sendCmd(Cmds.AUTH, pass);
    }

    if (clientName != null) {

      pipeline.sendCmd(Cmds.CLIENT, Cmds.SETNAME, clientName);
    }

    if (initReadOnly) {

      pipeline.sendCmd(ClusterCmds.READONLY);
    }

    pipeline.sync();
  }
}
