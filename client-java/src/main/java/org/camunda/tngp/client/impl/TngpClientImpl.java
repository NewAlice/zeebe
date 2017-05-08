package org.camunda.tngp.client.impl;

import static org.camunda.tngp.client.ClientProperties.BROKER_CONTACTPOINT;
import static org.camunda.tngp.client.ClientProperties.CLIENT_MAXCONNECTIONS;
import static org.camunda.tngp.client.ClientProperties.CLIENT_MAXREQUESTS;
import static org.camunda.tngp.client.ClientProperties.CLIENT_SENDBUFFER_SIZE;
import static org.camunda.tngp.client.ClientProperties.CLIENT_TASK_EXECUTION_AUTOCOMPLETE;
import static org.camunda.tngp.client.ClientProperties.CLIENT_TASK_EXECUTION_THREADS;
import static org.camunda.tngp.client.ClientProperties.CLIENT_THREADINGMODE;
import static org.camunda.tngp.client.ClientProperties.CLIENT_TCP_CHANNEL_KEEP_ALIVE_PERIOD;
import static org.camunda.tngp.util.EnsureUtil.ensureGreaterThanOrEqual;
import static org.camunda.tngp.util.EnsureUtil.ensureNotNullOrEmpty;

import java.util.Properties;

import org.camunda.tngp.client.ClientProperties;
import org.camunda.tngp.client.IncidentTopicClient;
import org.camunda.tngp.client.TngpClient;
import org.camunda.tngp.client.WorkflowTopicClient;
import org.camunda.tngp.client.event.impl.TopicClientImpl;
import org.camunda.tngp.client.impl.cmd.DummyChannelResolver;
import org.camunda.tngp.client.incident.impl.IncidentTopicClientImpl;
import org.camunda.tngp.client.task.impl.SubscriptionManager;
import org.camunda.tngp.dispatcher.Dispatcher;
import org.camunda.tngp.dispatcher.Dispatchers;
import org.camunda.tngp.transport.ClientChannel;
import org.camunda.tngp.transport.ReceiveBufferChannelHandler;
import org.camunda.tngp.transport.SocketAddress;
import org.camunda.tngp.transport.Transport;
import org.camunda.tngp.transport.TransportBuilder;
import org.camunda.tngp.transport.TransportBuilder.ThreadingMode;
import org.camunda.tngp.transport.Transports;
import org.camunda.tngp.transport.protocol.Protocols;
import org.camunda.tngp.transport.requestresponse.client.TransportConnectionPool;
import org.camunda.tngp.transport.singlemessage.DataFramePool;
import org.msgpack.jackson.dataformat.MessagePackFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TngpClientImpl implements TngpClient
{
    public static final int DEFAULT_RESOURCE_ID = 0;
    public static final int DEFAULT_SHARD_ID = 0;

    protected final Properties initializationProperties;

    protected final Transport transport;
    protected final TransportConnectionPool connectionPool;
    protected final DataFramePool dataFramePool;
    protected ClientChannel channel;
    protected SocketAddress contactPoint;
    protected Dispatcher dataFrameReceiveBuffer;

    protected DummyChannelResolver channelResolver;
    protected ClientCmdExecutor cmdExecutor;

    protected final ObjectMapper objectMapper;

    protected SubscriptionManager subscriptionManager;

    public TngpClientImpl(final Properties properties)
    {
        ClientProperties.setDefaults(properties);
        this.initializationProperties = properties;

        final String brokerContactPoint = properties.getProperty(BROKER_CONTACTPOINT);

        String hostName = brokerContactPoint;
        int port = -1;

        final int portDelimiter = hostName.indexOf(":");
        if (portDelimiter != -1)
        {
            hostName = hostName.substring(0, portDelimiter);
            port = Integer.parseInt(brokerContactPoint.substring(portDelimiter + 1, brokerContactPoint.length()));
        }
        else
        {
            final String errorMessage = String.format("Tngp Client config properts %s has wrong value: '%s' Needs to have format [hostname]:[port]", BROKER_CONTACTPOINT, brokerContactPoint);
            throw new RuntimeException(errorMessage);
        }

        contactPoint = new SocketAddress(hostName, port);
        final int maxConnections = Integer.parseInt(properties.getProperty(CLIENT_MAXCONNECTIONS));
        final int maxRequests = Integer.parseInt(properties.getProperty(CLIENT_MAXREQUESTS));
        final int sendBufferSize = Integer.parseInt(properties.getProperty(CLIENT_SENDBUFFER_SIZE));
        final ThreadingMode threadingMode = ThreadingMode.valueOf(properties.getProperty(CLIENT_THREADINGMODE));

        final TransportBuilder transportBuilder = Transports.createTransport("tngp.client")
            .sendBufferSize(1024 * 1024 * sendBufferSize)
            .maxMessageLength(1024 * 1024)
            .threadingMode(threadingMode);

        if (properties.containsKey(CLIENT_TCP_CHANNEL_KEEP_ALIVE_PERIOD))
        {
            transportBuilder.channelKeepAlivePeriod(Long.parseLong(properties.getProperty(CLIENT_TCP_CHANNEL_KEEP_ALIVE_PERIOD)));
        }

        transport = transportBuilder
            .build();

        dataFrameReceiveBuffer = Dispatchers.create("receive-buffer")
            .bufferSize(1024 * 1024 * sendBufferSize)
            .modePubSub()
            .frameMaxLength(1024 * 1024)
            .build();

        connectionPool = TransportConnectionPool.newFixedCapacityPool(transport, maxConnections, maxRequests);
        dataFramePool = DataFramePool.newBoundedPool(maxRequests, transport.getSendBuffer());

        channelResolver = new DummyChannelResolver();

        cmdExecutor = new ClientCmdExecutor(connectionPool, dataFramePool, channelResolver);

        objectMapper = new ObjectMapper(new MessagePackFactory());
        objectMapper.setSerializationInclusion(Include.NON_NULL);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        final int numExecutionThreads = Integer.parseInt(properties.getProperty(CLIENT_TASK_EXECUTION_THREADS));
        final Boolean autoCompleteTasks = Boolean.parseBoolean(properties.getProperty(CLIENT_TASK_EXECUTION_AUTOCOMPLETE));

        final int prefetchCapacity = Integer.parseInt(properties.getProperty(ClientProperties.CLIENT_TOPIC_SUBSCRIPTION_PREFETCH_CAPACITY));
        subscriptionManager = new SubscriptionManager(
                this,
                numExecutionThreads,
                autoCompleteTasks,
                prefetchCapacity,
                dataFrameReceiveBuffer.openSubscription("task-acquisition"));
        transport.registerChannelListener(subscriptionManager);
    }

    @Override
    public void connect()
    {
        channel = transport.createClientChannelPool()
                .requestResponseProtocol(connectionPool)
                .transportChannelHandler(Protocols.FULL_DUPLEX_SINGLE_MESSAGE, new ReceiveBufferChannelHandler(dataFrameReceiveBuffer))
                .build()
                .requestChannel(contactPoint);

        channelResolver.setChannelId(channel.getId());

        subscriptionManager.start();
    }

    @Override
    public void disconnect()
    {
        subscriptionManager.closeAllSubscriptions();

        subscriptionManager.stop();

        channel.close();
        channel = null;

        channelResolver.resetChannelId();
    }

    @Override
    public void close()
    {
        if (isConnected())
        {
            disconnect();
        }

        try
        {
            connectionPool.close();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            transport.close();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            dataFrameReceiveBuffer.close();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }
    }

    protected boolean isConnected()
    {
        return channel != null;
    }


    @Override
    public TransportConnectionPool getConnectionPool()
    {
        return connectionPool;
    }

    public DataFramePool getDataFramePool()
    {
        return dataFramePool;
    }

    @Override
    public TaskTopicClientImpl taskTopic(final String topicName, final int partitionId)
    {
        ensureNotNullOrEmpty("topic name", topicName);
        ensureGreaterThanOrEqual("partition id", partitionId, 0);
        return new TaskTopicClientImpl(this, topicName, partitionId);
    }

    @Override
    public WorkflowTopicClient workflowTopic(final String topicName, final int partitionId)
    {
        ensureNotNullOrEmpty("topic name", topicName);
        ensureGreaterThanOrEqual("partition id", partitionId, 0);
        return new WorkflowTopicClientImpl(this, topicName, partitionId);
    }

    @Override
    public IncidentTopicClient incidentTopic(String topicName, int partitionId)
    {
        ensureNotNullOrEmpty("topic name", topicName);
        ensureGreaterThanOrEqual("partition id", partitionId, 0);
        return new IncidentTopicClientImpl(this, topicName, partitionId);
    }

    @Override
    public TopicClientImpl topic(final String topicName, final int partitionId)
    {
        ensureNotNullOrEmpty("topic name", topicName);
        ensureGreaterThanOrEqual("partition id", partitionId, 0);
        return new TopicClientImpl(this, topicName, partitionId);
    }

    public ClientCmdExecutor getCmdExecutor()
    {
        return cmdExecutor;
    }

    public SubscriptionManager getSubscriptionManager()
    {
        return subscriptionManager;
    }

    public ObjectMapper getObjectMapper()
    {
        return objectMapper;
    }

    public Properties getInitializationProperties()
    {
        return initializationProperties;
    }

}
