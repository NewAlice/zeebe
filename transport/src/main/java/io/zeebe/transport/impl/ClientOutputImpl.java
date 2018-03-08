/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.transport.impl;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.agrona.DirectBuffer;

import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.transport.ClientOutput;
import io.zeebe.transport.ClientRequest;
import io.zeebe.transport.RemoteAddress;
import io.zeebe.transport.TransportMessage;
import io.zeebe.transport.impl.actor.ClientConductor;
import io.zeebe.util.buffer.BufferWriter;
import io.zeebe.util.sched.ActorScheduler;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;

public class ClientOutputImpl implements ClientOutput
{
    private final ClientConductor conductor;
    protected final Dispatcher sendBuffer;
    protected final ClientRequestPool requestPool;
    protected final Duration defaultRequestRetryTimeout;
    protected final ActorScheduler scheduler;

    public ClientOutputImpl(
            ClientConductor conductor,
            ActorScheduler scheduler,
            Dispatcher sendBuffer,
            ClientRequestPool requestPool,
            Duration defaultRequestRetryTimeout)
    {
        this.conductor = conductor;
        this.scheduler = scheduler;
        this.sendBuffer = sendBuffer;
        this.requestPool = requestPool;
        this.defaultRequestRetryTimeout = defaultRequestRetryTimeout;
    }

    @Override
    public boolean sendMessage(TransportMessage transportMessage)
    {
        return transportMessage.trySend(sendBuffer);
    }

    @Override
    public ClientRequest sendRequest(RemoteAddress addr, BufferWriter writer)
    {
        return requestPool.openRequest(addr, writer);
    }

    @Override
    public ActorFuture<ClientRequest> sendRequestWithRetry(RemoteAddress addr, BufferWriter writer, Duration timeout)
    {
        return sendRequestWithRetry(() -> CompletableActorFuture.completed(addr), (b) -> false, writer, timeout);
    }

    @Override
    public ActorFuture<ClientRequest> sendRequestWithRetry(RemoteAddress addr, BufferWriter writer)
    {
        return sendRequestWithRetry(addr, writer, defaultRequestRetryTimeout);
    }

    @Override
    public ActorFuture<ClientRequest> sendRequestWithRetry(Supplier<ActorFuture<RemoteAddress>> remoteAddressSupplier, Predicate<DirectBuffer> responseInspector,
            BufferWriter writer, Duration timeout)
    {
        final ClientRequestRetryController ctrl = new ClientRequestRetryController(
                conductor,
                remoteAddressSupplier,
                responseInspector,
                requestPool,
                writer,
                timeout);

        scheduler.submitActor(ctrl);

        return ctrl.getRequest();
    }

}
