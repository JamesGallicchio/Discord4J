/*
 * This file is part of Discord4J.
 *
 * Discord4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Discord4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Discord4J. If not, see <http://www.gnu.org/licenses/>.
 */
package discord4j.gateway;

import discord4j.common.ResettableInterval;
import discord4j.gateway.json.GatewayPayload;
import discord4j.gateway.json.Heartbeat;
import discord4j.gateway.json.Opcode;
import discord4j.gateway.json.dispatch.Dispatch;
import discord4j.gateway.json.dispatch.Ready;
import discord4j.gateway.json.dispatch.Resumed;
import discord4j.gateway.payload.PayloadReader;
import discord4j.gateway.payload.PayloadWriter;
import discord4j.gateway.retry.GatewayStateChange;
import discord4j.gateway.retry.RetryContext;
import discord4j.gateway.retry.RetryOptions;
import discord4j.websocket.CloseException;
import discord4j.websocket.WebSocketClient;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import reactor.core.Disposable;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.retry.Retry;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Represents a Discord gateway (websocket) client, implementing its lifecycle.
 * <p>
 * This is the next component downstream from {@link discord4j.websocket.WebSocketHandler}, that keeps track of
 * a single websocket session. It wraps an instance of {@link discord4j.gateway.DiscordWebSocketHandler} each time a
 * new connection to the gateway is made, therefore only one instance of this class is enough to handle the lifecycle
 * of Discord gateway operations, that could span multiple websocket sessions over time.
 * <p>
 * It provides automatic reconnecting through a configurable retry policy, allows downstream consumers to receive
 * inbound events through {@link #dispatch()} and direct raw payloads through {@link #receiver()} and provides
 * {@link #sender()} to submit events.
 */
public class DefaultGatewayClient implements GatewayClient {

    private static final Logger log = Loggers.getLogger(DefaultGatewayClient.class);
    private static final Predicate<? super Throwable> ABNORMAL_ERROR = t ->
            !(t instanceof CloseException) || ((CloseException) t).getCode() != 1000;

    private final WebSocketClient webSocketClient = new WebSocketClient(createHttpClient());

    private final PayloadReader payloadReader;
    private final PayloadWriter payloadWriter;
    private final RetryOptions retryOptions;
    private final IdentifyOptions identifyOptions;
    private final String token;

    private final EmitterProcessor<Dispatch> dispatch = EmitterProcessor.create(false);
    private final EmitterProcessor<GatewayPayload<?>> receiver = EmitterProcessor.create(false);
    private final EmitterProcessor<GatewayPayload<?>> sender = EmitterProcessor.create(false);

    private final AtomicBoolean resumable = new AtomicBoolean(true);
    private final AtomicInteger lastSequence = new AtomicInteger(0);
    private final ResettableInterval heartbeat = new ResettableInterval();
    private final AtomicReference<String> sessionId = new AtomicReference<>("");

    private final FluxSink<Dispatch> dispatchSink;
    private final FluxSink<GatewayPayload<?>> receiverSink;
    private final FluxSink<GatewayPayload<?>> senderSink;

    public DefaultGatewayClient(PayloadReader payloadReader, PayloadWriter payloadWriter,
            RetryOptions retryOptions, String token, IdentifyOptions identifyOptions) {
        this.payloadReader = Objects.requireNonNull(payloadReader);
        this.payloadWriter = Objects.requireNonNull(payloadWriter);
        this.retryOptions = Objects.requireNonNull(retryOptions);
        this.identifyOptions = Objects.requireNonNull(identifyOptions);
        this.token = Objects.requireNonNull(token);

        // initialize the sinks to safely produce values downstream
        // we use LATEST backpressure handling to avoid overflow on no subscriber situations
        this.dispatchSink = dispatch.sink(FluxSink.OverflowStrategy.LATEST);
        this.receiverSink = receiver.sink(FluxSink.OverflowStrategy.LATEST);
        this.senderSink = sender.sink(FluxSink.OverflowStrategy.LATEST);
    }

    @Override
    public Mono<Void> execute(String gatewayUrl) {
        return Mono.defer(() -> {
            final DiscordWebSocketHandler handler = new DiscordWebSocketHandler(payloadReader, payloadWriter);

            if (identifyOptions.getResumeSequence() != null) {
                this.lastSequence.set(identifyOptions.getResumeSequence());
                this.sessionId.set(identifyOptions.getResumeSessionId());
            } else {
                resumable.set(false);
            }

            // Internally subscribe to Ready to signal completion of retry mechanism
            Disposable readySub = dispatch.filter(DefaultGatewayClient::isReadyOrResume).subscribe(d -> {
                RetryContext retryContext = retryOptions.getRetryContext();
                if (retryContext.getResetCount() == 0) {
                    dispatchSink.next(GatewayStateChange.connected());
                } else {
                    dispatchSink.next(GatewayStateChange.retrySucceeded(retryContext.getAttempts()));
                }
                retryContext.reset();
                identifyOptions.setResumeSessionId(sessionId.get());
                resumable.set(true);
            });

            // Subscribe each inbound GatewayPayload to the receiver sink
            Disposable inboundSub = handler.inbound().subscribe(receiverSink::next);

            // Subscribe the receiver to process and transform the inbound payloads into Dispatch events
            Disposable receiverSub = receiver.map(this::updateSequence)
                    .map(payload -> payloadContext(payload, handler, this))
                    .subscribe(PayloadHandlers::handle);

            // Subscribe the handler's outbound exchange with our outgoing signals
            // routing error and completion signals to close the gateway
            Disposable senderSub = sender.subscribe(handler.outbound()::next, t -> handler.close(), handler::close);

            // Create the heartbeat loop, and subscribe it using the sender sink
            Disposable heartbeatSub = heartbeat.ticks()
                    .map(l -> new Heartbeat(lastSequence.get()))
                    .map(GatewayPayload::heartbeat)
                    .subscribe(handler.outbound()::next);

            return webSocketClient.execute(gatewayUrl, handler)
                    .doOnCancel(() -> close(false))
                    .doOnTerminate(() -> {
                        inboundSub.dispose();
                        receiverSub.dispose();
                        senderSub.dispose();
                        heartbeatSub.dispose();
                        readySub.dispose();
                        heartbeat.stop();
                    });
        }).retryWhen(retryFactory())
                .doOnCancel(() -> dispatchSink.next(GatewayStateChange.disconnected()))
                .doOnTerminate(() -> dispatchSink.next(GatewayStateChange.disconnected()));
    }

    private HttpClient createHttpClient() {
        return HttpClient.create(
                opt -> opt.afterChannelInit(channel -> {
                    log.debug("Installing SSL close future listener");
                    final SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
                    if (sslHandler != null) {
                        sslHandler.sslCloseFuture().addListener(
                                (GenericFutureListener<Future<Channel>>) future -> {
                                    if (future.isSuccess()) {
                                        final Channel c = future.getNow();
                                        if (c.isActive()) {
                                            log.debug("Closing channel due to SSL handler closing");
                                            c.close();
                                        }
                                    }
                                });
                    }
                }));
    }

    private static boolean isReadyOrResume(Dispatch d) {
        return Ready.class.isAssignableFrom(d.getClass()) || Resumed.class.isAssignableFrom(d.getClass());
    }

    private GatewayPayload<?> updateSequence(GatewayPayload<?> payload) {
        if (payload.getSequence() != null) {
            lastSequence.set(payload.getSequence());
            identifyOptions.setResumeSequence(lastSequence.get());
        }
        return payload;
    }

    private PayloadContext<?> payloadContext(GatewayPayload<?> payload, DiscordWebSocketHandler handler,
            DefaultGatewayClient client) {
        return new PayloadContext<>(payload, handler, client);
    }

    private Retry<RetryContext> retryFactory() {
        return Retry.<RetryContext>onlyIf(context -> ABNORMAL_ERROR.test(context.exception()))
                .withApplicationContext(retryOptions.getRetryContext())
                .backoff(retryOptions.getBackoff())
                .jitter(retryOptions.getJitter())
                .retryMax(retryOptions.getMaxRetries())
                .doOnRetry(context -> {
                    int attempt = context.applicationContext().getAttempts();
                    long backoff = context.backoff().toMillis();
                    log.debug("Retry attempt {} in {} ms", attempt, backoff);
                    if (attempt == 1) {
                        dispatchSink.next(GatewayStateChange.retryStarted(Duration.ofMillis(backoff)));
                    } else {
                        dispatchSink.next(GatewayStateChange.retryFailed(attempt - 1,
                                Duration.ofMillis(backoff)));
                        resumable.set(false);
                    }
                    context.applicationContext().next();
                });
    }

    @Override
    public void close(boolean reconnect) {
        if (reconnect) {
            senderSink.next(new GatewayPayload<>(Opcode.RECONNECT, null, null, null));
        } else {
            senderSink.next(new GatewayPayload<>());
            senderSink.complete();
        }
    }

    @Override
    public Flux<Dispatch> dispatch() {
        return dispatch;
    }

    @Override
    public Flux<GatewayPayload<?>> receiver() {
        return receiver;
    }

    @Override
    public FluxSink<GatewayPayload<?>> sender() {
        return senderSink;
    }

    @Override
    public String getSessionId() {
        return sessionId.get();
    }

    @Override
    public int getLastSequence() {
        return lastSequence.get();
    }

    ///////////////////////////////////////////
    // Fields for PayloadHandler consumption //
    ///////////////////////////////////////////

    /**
     * Obtains the FluxSink to send Dispatch events towards GatewayClient's users.
     *
     * @return a {@link reactor.core.publisher.FluxSink} for {@link discord4j.gateway.json.dispatch.Dispatch}
     *         objects
     */
    FluxSink<Dispatch> dispatchSink() {
        return dispatchSink;
    }

    /**
     * Gets the atomic reference for the current heartbeat sequence.
     *
     * @return an AtomicInteger representing the current gateway sequence
     */
    AtomicInteger lastSequence() {
        return lastSequence;
    }

    /**
     * Gets the atomic reference for the current session ID.
     *
     * @return an AtomicReference of the String representing the current session ID
     */
    AtomicReference<String> sessionId() {
        return sessionId;
    }

    /**
     * Gets the heartbeat manager bound to this GatewayClient.
     *
     * @return a {@link discord4j.common.ResettableInterval} to manipulate heartbeat operations
     */
    ResettableInterval heartbeat() {
        return heartbeat;
    }

    /**
     * Gets the token used to connect to the gateway.
     *
     * @return a token String
     */
    String token() {
        return token;
    }

    /**
     * An boolean value indicating if this client will attempt to RESUME.
     *
     * @return an AtomicBoolean representing resume capabilities
     */
    AtomicBoolean resumable() {
        return resumable;
    }

    /**
     * Gets the configuration object for gateway identifying procedure.
     *
     * @return an IdentifyOptions configuration object
     */
    IdentifyOptions identifyOptions() {
        return identifyOptions;
    }

    /**
     * Gets the configuration object for gateway reconnection procedure.
     *
     * @return a RetryOptions configuration object
     */
    RetryOptions retryOptions() {
        return retryOptions;
    }
}
