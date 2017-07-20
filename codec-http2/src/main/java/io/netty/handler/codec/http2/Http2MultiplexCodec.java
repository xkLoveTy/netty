/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPromiseNotifier;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http2.Http2CodecUtil.isOutboundStream;
import static io.netty.handler.codec.http2.Http2CodecUtil.isStreamIdValid;

/**
 * An HTTP/2 handler that creates child channels for each stream.
 *
 * <p>When a new stream is created, a new {@link Channel} is created for it. Applications send and
 * receive {@link Http2StreamFrame}s on the created channel. {@link ByteBuf}s cannot be processed by the channel;
 * all writes that reach the head of the pipeline must be an instance of {@link Http2StreamFrame}. Writes that reach
 * the head of the pipeline are processed directly by this handler and cannot be intercepted.
 *
 * <p>The child channel will be notified of user events that impact the stream, such as {@link
 * Http2GoAwayFrame} and {@link Http2ResetFrame}, as soon as they occur. Although {@code
 * Http2GoAwayFrame} and {@code Http2ResetFrame} signify that the remote is ignoring further
 * communication, closing of the channel is delayed until any inbound queue is drained with {@link
 * Channel#read()}, which follows the default behavior of channels in Netty. Applications are
 * free to close the channel in response to such events if they don't have use for any queued
 * messages.
 *
 * <p>Outbound streams are supported via the {@link Http2StreamChannelBootstrap}.
 *
 * <p>{@link ChannelConfig#setMaxMessagesPerRead(int)} and {@link ChannelConfig#setAutoRead(boolean)} are supported.
 *
 * <h3>Reference Counting</h3>
 *
 * Some {@link Http2StreamFrame}s implement the {@link ReferenceCounted} interface, as they carry
 * reference counted objects (e.g. {@link ByteBuf}s). The multiplex codec will call {@link ReferenceCounted#retain()}
 * before propagating a reference counted object through the pipeline, and thus an application handler needs to release
 * such an object after having consumed it. For more information on reference counting take a look at
 * http://netty.io/wiki/reference-counted-objects.html
 */
@UnstableApi
public class Http2MultiplexCodec extends Http2ChannelDuplexHandler {

    private static final InternalLogger LOG = InternalLoggerFactory.getInstance(Http2MultiplexCodec.class);

    // TODO: Use some sane initial capacity.
    private final Map<Http2FrameStream, Http2StreamChannel> channels =
            new ConcurrentHashMap<Http2FrameStream, Http2StreamChannel>();

    // Visible for testing
    final Http2StreamChannelBootstrap bootstrap;

    private final List<Http2StreamChannel> channelsToFireChildReadComplete = new ArrayList<Http2StreamChannel>();
    private final boolean server;
    // Visible for testing
    ChannelHandlerContext ctx;
    private volatile Runnable flushTask;

    private int initialOutboundStreamWindow = Http2CodecUtil.DEFAULT_WINDOW_SIZE;

    /**
     * Construct a new handler whose child channels run in a different event loop.
     *
     * @param server {@code true} this is a server
     * @param bootstrap bootstrap used to instantiate child channels for remotely-created streams.
     */
    public Http2MultiplexCodec(boolean server, Http2StreamChannelBootstrap bootstrap) {
        if (bootstrap.parentChannel() != null) {
            throw new IllegalStateException("The parent channel must not be set on the bootstrap.");
        }
        this.server = server;
        this.bootstrap = new Http2StreamChannelBootstrap(bootstrap);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        if (ctx.executor() != ctx.channel().eventLoop()) {
            throw new IllegalStateException("EventExecutor must be EventLoop of Channel");
        }
        bootstrap.parentChannel(ctx.channel());
        super.handlerAdded(ctx);
    }

    // Override this to signal it will never throw an exception.
    @Override
    public void flush(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof Http2FrameStreamEvent) {
            Http2FrameStreamEvent streamEvt = (Http2FrameStreamEvent) evt;
            switch (streamEvt.state()) {
                case CLOSED:
                    onStreamClosed(streamEvt.stream());
                    break;
                case ACTIVE:
                    onStreamActive(streamEvt.stream());
                    break;
                default:
                    throw new Error();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Http2Frame)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (msg instanceof Http2StreamFrame) {
            channelReadStreamFrame((Http2StreamFrame) msg);
        } else if (msg instanceof Http2GoAwayFrame) {
            final Http2GoAwayFrame goAwayFrame = (Http2GoAwayFrame) msg;
            try {
                forEachActiveStream(new Http2FrameStreamVisitor() {
                    @Override
                    public boolean visit(Http2FrameStream stream) {
                        final int streamId = stream.id();
                        final Http2StreamChannel childChannel = channels.get(stream);
                        if (streamId > goAwayFrame.lastStreamId() && isOutboundStream(server, streamId)) {
                            childChannel.pipeline().fireUserEventTriggered(goAwayFrame.retainedDuplicate());
                        }
                        return true;
                    }
                });
            } finally {
                // We need to ensure we release the goAwayFrame.
                goAwayFrame.release();
            }
        } else if (msg instanceof Http2SettingsFrame) {
            Http2Settings settings = ((Http2SettingsFrame) msg).settings();
            if (settings.initialWindowSize() != null) {
                initialOutboundStreamWindow = settings.initialWindowSize();
            }
        }
    }

    private void channelReadStreamFrame(Http2StreamFrame frame) {
        Http2FrameStream stream = frame.stream();

        Http2StreamChannel childChannel = channels.get(stream);

        // TODO: Should this happen now that onStreamActive(...) is called when an Http2FrameStreamEvent with state
        // ACTIVE is received.
        if (childChannel == null) {
            childChannel = onStreamActive(stream);
        }

        fireChildReadAndRegister(childChannel, frame);
    }

    private void onStreamClosed(Http2FrameStream stream) {
        Http2StreamChannel childChannel = channels.get(stream);
        if (childChannel != null) {
            childChannel.streamClosed();
        }
    }

    private Http2StreamChannel onStreamActive(Http2FrameStream stream) {
        Http2StreamChannel childChannel = channels.get(stream);
        if (childChannel == null) {
            ChannelFuture future = bootstrap.connect(stream);
            childChannel = (Http2StreamChannel) future.channel();
            channels.put(stream, childChannel);
        }

        assert !childChannel.isWritable();
        childChannel.incrementOutboundFlowControlWindow(initialOutboundStreamWindow);
        childChannel.pipeline().fireChannelWritabilityChanged();
        return childChannel;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof Http2FrameStreamException) {
            Http2FrameStreamException streamException = (Http2FrameStreamException) cause;
            Http2FrameStream stream = streamException.stream();
            Http2StreamChannel childChannel = channels.get(stream);

            try {
                childChannel.pipeline().fireExceptionCaught(streamException.getCause());
            } finally {
                childChannel.close();
            }
        } else {
            ctx.fireExceptionCaught(cause);
        }
    }

    private void fireChildReadAndRegister(Http2StreamChannel childChannel, Http2StreamFrame frame) {
        // Can't use childChannel.fireChannelRead() as it would fire independent of whether
        // channel.read() had been called.
        childChannel.fireChildRead(frame);
        if (!childChannel.inStreamsToFireChildReadComplete) {
            channelsToFireChildReadComplete.add(childChannel);
            childChannel.inStreamsToFireChildReadComplete = true;
        }
    }

    final void flushFromStreamChannel() {
        assert ctx.executor().inEventLoop();
        flush(ctx);
    }

    final void writeFromStreamChannel(final Http2Frame frame, final ChannelPromise promise, final boolean flush) {
        assert ctx.executor().inEventLoop();
        writeFromStreamChannel0(frame, flush, promise);
    }

    private void writeFromStreamChannel0(Http2Frame frame, boolean flush, ChannelPromise promise) {
        try {
            ctx.write(frame, promise);
        } catch (Throwable cause) {
            promise.tryFailure(cause);
        }
        if (flush) {
            flush(ctx);
        }
    }

    /**
     * Notifies any child streams of the read completion.
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        for (int i = 0; i < channelsToFireChildReadComplete.size(); i++) {
            Http2StreamChannel childChannel = channelsToFireChildReadComplete.get(i);
            // Clear early in case fireChildReadComplete() causes it to need to be re-processed
            childChannel.inStreamsToFireChildReadComplete = false;
            childChannel.fireChildReadComplete();
        }
        channelsToFireChildReadComplete.clear();
    }

    ChannelFuture createStreamChannel(Channel parentChannel, ChannelHandler handler,
                                              Map<ChannelOption<?>, Object> options,
                                              Map<AttributeKey<?>, Object> attrs,
                                              Http2FrameStream stream) {
        final Http2StreamChannel childChannel = new Http2StreamChannel(parentChannel, stream);
        childChannel.pipeline().addLast(handler);

        initOpts(childChannel, options);
        initAttrs(childChannel, attrs);

        ChannelFuture future = parentChannel.eventLoop().register(childChannel);
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                // Handle any errors that occurred on the local thread while registering. Even though
                // failures can happen after this point, they will be handled by the channel by closing the
                // childChannel.
                if (future.cause() != null) {
                    if (childChannel.isRegistered()) {
                        childChannel.close();
                    } else {
                        childChannel.unsafe().closeForcibly();
                    }
                }
            }
        });
        return future;
    }

    @SuppressWarnings("unchecked")
    private static void initOpts(Channel channel, Map<ChannelOption<?>, Object> opts) {
        if (opts != null) {
            for (Entry<ChannelOption<?>, Object> e: opts.entrySet()) {
                try {
                    if (!channel.config().setOption((ChannelOption<Object>) e.getKey(), e.getValue())) {
                        LOG.warn("Unknown channel option: " + e);
                    }
                } catch (Throwable t) {
                    LOG.warn("Failed to set a channel option: " + channel, t);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void initAttrs(Channel channel, Map<AttributeKey<?>, Object> attrs) {
        if (attrs != null) {
            for (Entry<AttributeKey<?>, Object> e: attrs.entrySet()) {
                channel.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
            }
        }
    }

    final class Http2StreamChannel extends AbstractHttp2StreamChannel {

        /** {@code true} after the first HEADERS frame has been written **/
        boolean firstFrameWritten;

        /** {@code true} if a close without an error was initiated **/
        boolean streamClosedWithoutError;

        /** {@code true} if stream is in {@link Http2MultiplexCodec#channelsToFireChildReadComplete}. **/
        boolean inStreamsToFireChildReadComplete;

        Http2StreamChannel(Channel parentChannel, Http2FrameStream stream) {
            super(parentChannel, stream);
            channels.put(stream, this);
        }

        void streamClosed() {
            streamClosedWithoutError = true;
            fireChildRead(AbstractHttp2StreamChannel.CLOSE_MESSAGE);
        }

        @Override
        protected void doClose() throws Exception {
            if (!streamClosedWithoutError && isStreamIdValid(stream().id())) {
                Http2StreamFrame resetFrame = new DefaultHttp2ResetFrame(Http2Error.CANCEL).stream(stream());
                writeFromStreamChannel(resetFrame, ctx.newPromise(), true);
            }
            super.doClose();
        }

        @Override
        protected void doWrite(Object msg, ChannelPromise childPromise) {
            if (msg instanceof Http2StreamFrame) {
                Http2StreamFrame frame = validateStreamFrame(msg);
                if (!firstFrameWritten && !isStreamIdValid(stream().id())) {
                    if (!(frame instanceof Http2HeadersFrame)) {
                        throw new IllegalArgumentException("The first frame must be a headers frame. Was: "
                                + frame.name());
                    }
                    childPromise.unvoid().addListener(new FirstWriteChannelFutureListener());
                    firstFrameWritten = true;
                }
                frame.stream(stream());

                /**
                 * Wrap the ChannelPromise of the child channel in a ChannelPromise of the parent channel
                 * in order to be able to use it on the parent channel. We don't need to worry about the
                 * channel being cancelled, as the outbound buffer of the child channel marks it uncancelable.
                 */
                assert !childPromise.isCancellable();
                ChannelFutureListener childPromiseNotifier = new ChannelPromiseNotifier(childPromise);
                ChannelPromise parentPromise = ctx.newPromise().addListener(childPromiseNotifier);
                writeFromStreamChannel(frame, parentPromise, false);
            } else if (msg instanceof Http2GoAwayFrame) {
                writeFromStreamChannel((Http2GoAwayFrame) msg, ctx.newPromise(), false);
            } else {
                ReferenceCountUtil.release(msg);
                throw new IllegalArgumentException("Message must be an Http2GoAwayFrame or Http2StreamFrame: " + msg);
            }
        }

        @Override
        protected void doWriteComplete() {
            flushFromStreamChannel();
        }

        @Override
        protected void bytesConsumed(final int bytes) {
            ctx.write(new DefaultHttp2WindowUpdateFrame(bytes).stream(stream()));
        }

        private Http2StreamFrame validateStreamFrame(Object msg) {
            if (!(msg instanceof Http2StreamFrame)) {
                String msgString = msg.toString();
                ReferenceCountUtil.release(msg);
                throw new IllegalArgumentException("Message must be a Http2StreamFrame: " + msgString);
            }
            Http2StreamFrame frame = (Http2StreamFrame) msg;
            if (frame.stream() != null) {
                String msgString = msg.toString();
                ReferenceCountUtil.release(frame);
                throw new IllegalArgumentException("Stream must not be set on the frame: " + msgString);
            }
            return frame;
        }

        private final class FirstWriteChannelFutureListener implements ChannelFutureListener {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    Http2FrameStream stream = stream();
                    onStreamActive(stream);
                } else {
                    pipeline().fireExceptionCaught(future.cause());
                    close();
                }
            }
        }
    }
}
