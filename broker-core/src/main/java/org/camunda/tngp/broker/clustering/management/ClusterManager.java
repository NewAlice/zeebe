package org.camunda.tngp.broker.clustering.management;

import static org.camunda.tngp.broker.clustering.ClusterServiceNames.PEER_LOCAL_SERVICE;
import static org.camunda.tngp.broker.clustering.ClusterServiceNames.RAFT_SERVICE_GROUP;
import static org.camunda.tngp.broker.clustering.ClusterServiceNames.clientChannelManagerName;
import static org.camunda.tngp.broker.clustering.ClusterServiceNames.raftContextServiceName;
import static org.camunda.tngp.broker.clustering.ClusterServiceNames.raftServiceName;
import static org.camunda.tngp.broker.clustering.ClusterServiceNames.subscriptionServiceName;
import static org.camunda.tngp.broker.clustering.ClusterServiceNames.transportConnectionPoolName;
import static org.camunda.tngp.broker.system.SystemServiceNames.AGENT_RUNNER_SERVICE;
import static org.camunda.tngp.broker.transport.TransportServiceNames.REPLICATION_SOCKET_BINDING_NAME;
import static org.camunda.tngp.broker.transport.TransportServiceNames.TRANSPORT;
import static org.camunda.tngp.broker.transport.TransportServiceNames.TRANSPORT_SEND_BUFFER;
import static org.camunda.tngp.broker.transport.TransportServiceNames.serverSocketBindingReceiveBufferName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.camunda.tngp.broker.clustering.channel.ClientChannelManagerService;
import org.camunda.tngp.broker.clustering.gossip.data.Peer;
import org.camunda.tngp.broker.clustering.management.config.ClusterManagementConfig;
import org.camunda.tngp.broker.clustering.management.handler.ClusterManagerFragmentHandler;
import org.camunda.tngp.broker.clustering.management.message.InvitationRequest;
import org.camunda.tngp.broker.clustering.management.message.InvitationResponse;
import org.camunda.tngp.broker.clustering.raft.Member;
import org.camunda.tngp.broker.clustering.raft.MetaStore;
import org.camunda.tngp.broker.clustering.raft.Raft;
import org.camunda.tngp.broker.clustering.raft.RaftContext;
import org.camunda.tngp.broker.clustering.raft.service.RaftContextService;
import org.camunda.tngp.broker.clustering.raft.service.RaftService;
import org.camunda.tngp.broker.clustering.service.SubscriptionService;
import org.camunda.tngp.broker.clustering.service.TransportConnectionPoolService;
import org.camunda.tngp.broker.clustering.util.MessageWriter;
import org.camunda.tngp.broker.clustering.util.RequestResponseController;
import org.camunda.tngp.broker.logstreams.LogStreamsManager;
import org.camunda.tngp.dispatcher.FragmentHandler;
import org.camunda.tngp.dispatcher.Subscription;
import org.camunda.tngp.logstreams.impl.log.fs.FsLogStorage;
import org.camunda.tngp.logstreams.log.LogStream;
import org.camunda.tngp.servicecontainer.ServiceContainer;
import org.camunda.tngp.servicecontainer.ServiceName;
import org.camunda.tngp.transport.ClientChannelPool;
import org.camunda.tngp.transport.protocol.Protocols;
import org.camunda.tngp.transport.requestresponse.client.TransportConnectionPool;

public class ClusterManager implements Agent
{
    private final ClusterManagerContext context;
    private final ServiceContainer serviceContainer;

    private final List<Raft> rafts;

    private final ManyToOneConcurrentArrayQueue<Runnable> managementCmdQueue;
    private final Consumer<Runnable> commandConsumer;

    private final List<RequestResponseController> activeRequestController;

    private final ClusterManagerFragmentHandler fragmentHandler;

    private final InvitationRequest invitationRequest;
    private final InvitationResponse invitationResponse;

    private ClusterManagementConfig config;

    private final MessageWriter messageWriter;

    public ClusterManager(final ClusterManagerContext context, final ServiceContainer serviceContainer, ClusterManagementConfig config)
    {
        this.context = context;
        this.serviceContainer = serviceContainer;
        this.config = config;
        this.rafts = new CopyOnWriteArrayList<>();
        this.managementCmdQueue = new ManyToOneConcurrentArrayQueue<>(100);
        this.commandConsumer = (r) -> r.run();
        this.activeRequestController = new CopyOnWriteArrayList<>();
        this.invitationRequest = new InvitationRequest();

        this.fragmentHandler = new ClusterManagerFragmentHandler(this, context.getSubscription());
        this.invitationResponse = new InvitationResponse();
        this.messageWriter = new MessageWriter(context.getSendBuffer());

        context.getPeers().registerListener((p) -> addPeer(p));
    }

    public void open()
    {
        String metaDirectory = config.metaDirectory;

        if (metaDirectory == null || metaDirectory.isEmpty())
        {
            try
            {
                final File tempDir = Files.createTempDirectory("tngp-meta-").toFile();
                metaDirectory = tempDir.getAbsolutePath();
            }
            catch (IOException e)
            {
                throw new RuntimeException("Could not create temp directory for meta data", e);
            }
        }

        config.metaDirectory = metaDirectory;

        final LogStreamsManager logStreamManager = context.getLogStreamsManager();

        final File dir = new File(metaDirectory);

        if (!dir.exists())
        {
            try
            {
                dir.getParentFile().mkdirs();
                Files.createDirectory(dir.toPath());
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        final File[] metaFiles = dir.listFiles();

        if (metaFiles != null && metaFiles.length > 0)
        {
            for (int i = 0; i < metaFiles.length; i++)
            {
                final File file = metaFiles[i];
                final MetaStore meta = new MetaStore(file.getAbsolutePath());

                final int partitionId = meta.loadPartitionId();
                final DirectBuffer topicName = meta.loadTopicName();

                LogStream logStream = logStreamManager.getLogStream(topicName, partitionId);

                if (logStream == null)
                {
                    final String directory = meta.loadLogDirectory();
                    logStream = logStreamManager.createLogStream(topicName, partitionId, directory);
                }

                createRaft(logStream, meta, Collections.emptyList(), false);
            }
        }
        else if (context.getPeers().sizeVolatile() == 1)
        {
            logStreamManager.forEachLogStream(logStream -> createRaft(logStream, Collections.emptyList(), true));
        }
    }

    @Override
    public String roleName()
    {
        return "management";
    }

    @Override
    public int doWork() throws Exception
    {
        int workcount = 0;

        workcount += managementCmdQueue.drain(commandConsumer);
        workcount += fragmentHandler.doWork();

        int i = 0;
        while (i < activeRequestController.size())
        {
            final RequestResponseController requestController = activeRequestController.get(i);
            workcount += requestController.doWork();

            if (requestController.isFailed() || requestController.isResponseAvailable())
            {
                requestController.close();
            }

            if (requestController.isClosed())
            {
                activeRequestController.remove(i);
            }
            else
            {
                i++;
            }
        }

        return workcount;
    }

    public void addPeer(final Peer peer)
    {
        final Peer copy = new Peer();
        copy.wrap(peer);
        managementCmdQueue.add(() ->
        {

            for (int i = 0; i < rafts.size(); i++)
            {
                final Raft raft = rafts.get(i);
                if (raft.needMembers())
                {
                    // TODO: if this should be garbage free, we have to limit
                    // the number of concurrent invitations.
                    final LogStream logStream = raft.stream();
                    final InvitationRequest invitationRequest = new InvitationRequest()
                        .topicName(logStream.getTopicName())
                        .partitionId(logStream.getPartitionId())
                        .term(raft.term())
                        .members(raft.configuration().members());

                    final ClientChannelPool clientChannelManager = context.getClientChannelPool();
                    final TransportConnectionPool connections = context.getConnections();
                    final RequestResponseController requestController = new RequestResponseController(clientChannelManager, connections);
                    requestController.open(copy.managementEndpoint(), invitationRequest);
                    activeRequestController.add(requestController);
                }
            }

        });
    }

    public void addRaft(final Raft raft)
    {
        managementCmdQueue.add(() ->
        {
            rafts.add(raft);
        });
    }

    public void removeRaft(final Raft raft)
    {
        final LogStream logStream = raft.stream();
        final DirectBuffer topicName = logStream.getTopicName();
        final int partitionId = logStream.getPartitionId();

        managementCmdQueue.add(() ->
        {
            for (int i = 0; i < rafts.size(); i++)
            {
                final Raft r = rafts.get(i);
                final LogStream stream = r.stream();
                if (topicName.equals(stream.getTopicName()) && partitionId == stream.getPartitionId())
                {
                    rafts.remove(i);
                    break;
                }
            }
        });
    }

    public void createRaft(LogStream logStream, List<Member> members, boolean bootstrap)
    {
        final FsLogStorage logStorage = (FsLogStorage) logStream.getLogStorage();
        final String path = logStorage.getConfig().getPath();

        final MetaStore meta = new MetaStore(this.config.metaDirectory + File.separator + String.format("%s.meta", logStream.getLogName()));
        meta.storeTopicNameAndPartitionIdAndDirectory(logStream.getTopicName(), logStream.getPartitionId(), path);

        createRaft(logStream, meta, members, bootstrap);
    }

    public void createRaft(LogStream logStream, MetaStore meta, List<Member> members, boolean bootstrap)
    {
        final String logName = logStream.getLogName();
        final String component = String.format("raft.%s", logName);

        final TransportConnectionPoolService transportConnectionPoolService = new TransportConnectionPoolService();

        final ServiceName<TransportConnectionPool> transportConnectionPoolServiceName = transportConnectionPoolName(component);
        serviceContainer.createService(transportConnectionPoolServiceName, transportConnectionPoolService)
            .dependency(TRANSPORT, transportConnectionPoolService.getTransportInjector())
            .install();

        // TODO: make it configurable
        final ClientChannelManagerService clientChannelManagerService = new ClientChannelManagerService(128);
        final ServiceName<ClientChannelPool> clientChannelManagerServiceName = clientChannelManagerName(component);
        serviceContainer.createService(clientChannelManagerServiceName, clientChannelManagerService)
            .dependency(TRANSPORT, clientChannelManagerService.getTransportInjector())
            .dependency(transportConnectionPoolServiceName, clientChannelManagerService.getTransportConnectionPoolInjector())
            .dependency(serverSocketBindingReceiveBufferName(REPLICATION_SOCKET_BINDING_NAME), clientChannelManagerService.getReceiveBufferInjector())
            .install();

        final SubscriptionService subscriptionService = new SubscriptionService();
        final ServiceName<Subscription> subscriptionServiceName = subscriptionServiceName(component);
        serviceContainer.createService(subscriptionServiceName, subscriptionService)
            .dependency(serverSocketBindingReceiveBufferName(REPLICATION_SOCKET_BINDING_NAME), subscriptionService.getReceiveBufferInjector())
            .install();

        // TODO: provide raft configuration
        final RaftContextService raftContextService = new RaftContextService(serviceContainer);
        final ServiceName<RaftContext> raftContextServiceName = raftContextServiceName(logName);
        serviceContainer.createService(raftContextServiceName, raftContextService)
            .dependency(PEER_LOCAL_SERVICE, raftContextService.getLocalPeerInjector())
            .dependency(TRANSPORT_SEND_BUFFER, raftContextService.getSendBufferInjector())
            .dependency(clientChannelManagerServiceName, raftContextService.getClientChannelManagerInjector())
            .dependency(transportConnectionPoolServiceName, raftContextService.getTransportConnectionPoolInjector())
            .dependency(subscriptionServiceName, raftContextService.getSubscriptionInjector())
            .dependency(AGENT_RUNNER_SERVICE, raftContextService.getAgentRunnerInjector())
            .install();

        final RaftService raftService = new RaftService(logStream, meta, new CopyOnWriteArrayList<>(members), bootstrap);
        final ServiceName<Raft> raftServiceName = raftServiceName(logName);
        serviceContainer.createService(raftServiceName, raftService)
            .group(RAFT_SERVICE_GROUP)
            .dependency(AGENT_RUNNER_SERVICE, raftService.getAgentRunnerInjector())
            .dependency(raftContextServiceName, raftService.getRaftContextInjector())
            .install();
    }

    public int onInvitationRequest(final DirectBuffer buffer, final int offset, final int length, final int channelId, final long connectionId, final long requestId)
    {
        invitationRequest.reset();
        invitationRequest.wrap(buffer, offset, length);

        final DirectBuffer topicName = invitationRequest.topicName();
        final int partitionId = invitationRequest.partitionId();

        final LogStreamsManager logStreamManager = context.getLogStreamsManager();
        final LogStream logStream = logStreamManager.createLogStream(topicName, partitionId);

        createRaft(logStream, new ArrayList<>(invitationRequest.members()), false);

        invitationResponse.reset();
        messageWriter.protocol(Protocols.REQUEST_RESPONSE)
            .channelId(channelId)
            .connectionId(connectionId)
            .requestId(requestId)
            .message(invitationResponse)
            .tryWriteMessage();

        return FragmentHandler.CONSUME_FRAGMENT_RESULT;
    }

}
