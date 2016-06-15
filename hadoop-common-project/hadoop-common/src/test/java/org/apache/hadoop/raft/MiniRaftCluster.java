/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.raft;

import org.apache.hadoop.raft.client.RaftClient;
import org.apache.hadoop.raft.protocol.RaftClientReply;
import org.apache.hadoop.raft.protocol.RaftClientRequest;
import org.apache.hadoop.raft.server.RaftConfiguration;
import org.apache.hadoop.raft.server.RaftConstants;
import org.apache.hadoop.raft.server.RaftServer;
import org.apache.hadoop.raft.protocol.RaftPeer;
import org.apache.hadoop.raft.server.ServerState;
import org.apache.hadoop.raft.server.protocol.RaftLogEntry;
import org.apache.hadoop.raft.server.protocol.RaftServerReply;
import org.apache.hadoop.raft.server.protocol.RaftServerRequest;
import org.apache.hadoop.raft.server.simulation.SimulatedRpc;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class MiniRaftCluster {

  static final Logger LOG = LoggerFactory.getLogger(MiniRaftCluster.class);

  public static class PeerChanges {
    public final RaftPeer[] allPeersInNewConf;
    public final RaftPeer[] newPeers;
    public final RaftPeer[] removedPeers;

    public PeerChanges(RaftPeer[] all, RaftPeer[] newPeers, RaftPeer[] removed) {
      this.allPeersInNewConf = all;
      this.newPeers = newPeers;
      this.removedPeers = removed;
    }
  }

  private static RaftConfiguration initConfiguration(int num) {
    RaftPeer[] peers = new RaftPeer[num];
    for (int i = 0; i < num; i++) {
      peers[i] = new RaftPeer("s" + i);
    }
    return new RaftConfiguration(peers, 0);
  }

  private RaftConfiguration conf;
  private final SimulatedRpc<RaftServerRequest, RaftServerReply> serverRpc;
  private final SimulatedRpc<RaftClientRequest, RaftClientReply> client2serverRpc;
  private final Map<String, RaftServer> servers = new LinkedHashMap<>();

  public MiniRaftCluster(int numServers) {
    this.conf = initConfiguration(numServers);
    serverRpc = new SimulatedRpc<>(conf.getPeers());
    client2serverRpc = new SimulatedRpc<>(conf.getPeers());

    for (RaftPeer p : conf.getPeers()) {
      servers.put(p.getId(), new RaftServer(p.getId(), conf, serverRpc,
          client2serverRpc));
    }
  }

  public void start() {
    servers.values().forEach((server) -> server.start(conf));
  }

  public PeerChanges addNewPeers(int number, boolean startNewPeer) {
    List<RaftPeer> newPeers = new ArrayList<>(number);
    final int oldSize = conf.getPeers().size();
    for (int i = oldSize; i < oldSize + number; i++) {
      newPeers.add(new RaftPeer("s" + i));
    }
    final RaftPeer[] np = newPeers.toArray(new RaftPeer[newPeers.size()]);

    serverRpc.addPeers(newPeers);
    client2serverRpc.addPeers(newPeers);

    // create and add new RaftServers
    for (RaftPeer p : newPeers) {
      RaftServer newServer = new RaftServer(p.getId(), conf, serverRpc,
          client2serverRpc);
      servers.put(p.getId(), newServer);
      if (startNewPeer) {
        // start new peers as initializing state
        newServer.start(null);
      }
    }

    newPeers.addAll(conf.getPeers());
    RaftPeer[] p = newPeers.toArray(new RaftPeer[newPeers.size()]);
    conf = new RaftConfiguration(p, 0);
    return new PeerChanges(p, np, new RaftPeer[0]);
  }

  public void startServer(String id, RaftConfiguration conf) {
    RaftServer server = servers.get(id);
    assert server != null;
    server.start(conf);
  }

  public void enforceServerLog(String id, List<RaftLogEntry> newLogEntries,
      RaftConfiguration conf) {
    RaftServer server = servers.get(id);
    assert server != null;
    ServerState newServerState = ServerState.buildServerState(server.getState(),
        newLogEntries);
    server.kill();
    RaftServer newServer = new RaftServer(id, newServerState, serverRpc,
        client2serverRpc);
    servers.put(id, newServer);
    newServer.start(conf);
  }

  /**
   * prepare the peer list when removing some peers from the conf
   */
  public PeerChanges removePeers(int number, boolean removeLeader,
      Collection<RaftPeer> excluded) {
    Collection<RaftPeer> peers = new ArrayList<>(conf.getPeers());
    List<RaftPeer> removedPeers = new ArrayList<>(number);
    if (removeLeader) {
      final RaftPeer leader = new RaftPeer(getLeader().getId());
      assert !excluded.contains(leader);
      peers.remove(leader);
      removedPeers.add(leader);
    }
    List<RaftServer> followers = getFollowers();
    for (int i = 0, removed = 0; i < followers.size() &&
        removed < (removeLeader ? number - 1 : number); i++) {
      RaftPeer toRemove = new RaftPeer(followers.get(i).getId());
      if (!excluded.contains(toRemove)) {
        peers.remove(toRemove);
        removedPeers.add(toRemove);
        removed++;
      }
    }
    RaftPeer[] p = peers.toArray(new RaftPeer[peers.size()]);
    conf = new RaftConfiguration(p, 0);
    return new PeerChanges(p, new RaftPeer[0],
        removedPeers.toArray(new RaftPeer[removedPeers.size()]));
  }

  void killServer(String id) {
    servers.get(id).kill();
  }

  public String printServers() {
    StringBuilder b = new StringBuilder("\n#servers = " + servers.size() + "\n");
    for (RaftServer s : servers.values()) {
      b.append("  ");
      b.append(s).append("\n");
    }
    return b.toString();
  }

  String printAllLogs() {
    StringBuilder b = new StringBuilder("\n#servers = " + servers.size() + "\n");
    for (RaftServer s : servers.values()) {
      b.append("  ");
      b.append(s).append("\n");
      b.append("    ");
      b.append(s.getState().getLog().getEntryString());
    }
    return b.toString();
  }

  public RaftServer getLeader() {
    final List<RaftServer> leaders = servers.values().stream()
        .filter(s -> s.isRunning() && s.isLeader())
        .collect(Collectors.toList());
    if (leaders.isEmpty()) {
      return null;
    } else {
      Assert.assertEquals(1, leaders.size());
      return leaders.get(0);
    }
  }

  List<RaftServer> getFollowers() {
    return servers.values().stream()
        .filter(s -> s.isRunning() && s.isFollower())
        .collect(Collectors.toList());
  }

  public Collection<RaftServer> getServers() {
    return servers.values();
  }

  public RaftClient createClient(String clientId, String leaderId) {
    return new RaftClient(clientId, conf.getPeers(), client2serverRpc, leaderId);
  }

  public void shutdown() {
    servers.values().stream().filter(RaftServer::isRunning)
        .forEach(RaftServer::kill);
  }

  /**
   * Try to enforce the leader of the cluster.
   * @param leaderId ID of the targeted leader server.
   * @return true if server has been successfully enforced to the leader, false
   *         otherwise.
   * @throws InterruptedException
   */
  public boolean tryEnforceLeader(String leaderId)
      throws InterruptedException {
    final RaftServer leader = getLeader();
    if (leader != null && leader.getId().equals(leaderId)) {
      return true;
    }
    // Blocking all other server's RPC read process to make sure a read takes at
    // least ELECTION_TIMEOUT_MIN. In this way when the target leader request a
    // vote, all non-leader servers can grant the vote.
    LOG.debug("begin blocking queue for target leader");
    for (Map.Entry<String, RaftServer> e : servers.entrySet()) {
      if (!e.getKey().equals(leaderId)) {
        serverRpc.setTakeRequestDelayMs(e.getKey(),
            RaftConstants.ELECTION_TIMEOUT_MIN_MS);
      }
    }
    // Disable the RPC queue for the target leader server so that it can request
    // a vote.
    serverRpc.setIsOpenForMessage(leaderId, false);
    LOG.debug("Closed queue for target leader");

    Thread.sleep(RaftConstants.ELECTION_TIMEOUT_MAX_MS + 100);
    LOG.debug("target leader should have became candidate. open queue");

    // Reopen queues so that the vote can make progress.
    for (Map.Entry<String, RaftServer> e : servers.entrySet()) {
      if (!e.getKey().equals(leaderId)) {
        serverRpc.setTakeRequestDelayMs(e.getKey(), 0);
      }
    }
    serverRpc.setIsOpenForMessage(leaderId, true);
    // Wait for a quiescence.
    Thread.sleep(RaftConstants.ELECTION_TIMEOUT_MAX_MS + 100);

    return servers.get(leaderId).isLeader();
  }

  SimulatedRpc<RaftServerRequest, RaftServerReply> getServerRpc() {
    return serverRpc;
  }

  public RaftServer getRaftServer(String id) {
    return servers.get(id);
  }
}
