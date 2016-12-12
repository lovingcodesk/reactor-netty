/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.channel;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.NettyOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.QueueSupplier;

/**
 * Netty {@link io.netty.channel.ChannelDuplexHandler} implementation that bridge data
 * via an IPC {@link NettyOutbound}
 *
 * @author Stephane Maldini
 */
final class ChannelOperationsHandler extends ChannelDuplexHandler
		implements ChannelFutureListener {

	final Queue<?>                            pendingWrites;
	final PublisherSender                     inner;
	/**
	 * Cast the supplied queue (SpscLinkedArrayQueue) to use its atomic dual-insert
	 * backed by {@link BiPredicate#test)
	 **/
	final BiPredicate<ChannelPromise, Object> pendingWriteOffer;
	final BiConsumer<?, ? super ByteBuf>      encoder;
	final int                                 prefetch;
	final int                                 limit;

	long                  pendingBytes;
	ChannelHandlerContext ctx;

	volatile boolean innerActive;
	volatile boolean removed;
	volatile int     wip;

	@SuppressWarnings("unchecked")
	ChannelOperationsHandler() {
		this.pendingWrites = QueueSupplier.unbounded()
		                                  .get();
		this.pendingWriteOffer = (BiPredicate<ChannelPromise, Object>) pendingWrites;
		this.inner = new PublisherSender(this);
		this.prefetch = 32;
		this.limit = prefetch - (prefetch >> 2);
		this.encoder = NOOP_ENCODER;
	}

	@Override
	final public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		try {
			ChannelOperations<?, ?> ops = inbound();
			if(ops != null){
				ops.onChannelInactive();
			}
		}
		catch (Throwable err) {
			Exceptions.throwIfFatal(err);
			exceptionCaught(ctx, err);
		}
		finally {
			ctx.fireChannelInactive();
		}
	}

	@Override
	final public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg == null) {
			return;
		}
		try {
			if (msg == Unpooled.EMPTY_BUFFER || msg instanceof EmptyByteBuf) {
				return;
			}
			inbound().onInboundNext(ctx, msg);
			ctx.fireChannelRead(msg);
		}
		catch (Throwable err) {
			Exceptions.throwIfFatal(err);
			exceptionCaught(ctx, err);
		}
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
		if (log.isDebugEnabled()) {
			log.debug("Write state change {}",
					ctx.channel()
					   .isWritable());
		}
		ctx.fireChannelWritabilityChanged();
		if (ctx.channel()
		       .isWritable()) {
			inner.request(1L);
		}
		drain();
	}

	@Override
	final public void exceptionCaught(ChannelHandlerContext ctx, Throwable err)
			throws Exception {
		if(log.isDebugEnabled()){
			log.error("handler failure", err);
		}
		Exceptions.throwIfFatal(err);
		ChannelOperations<?, ?> ops = inbound();
		if(ops != null){
			ops.onInboundError(err);
		}
	}

	@Override
	public void flush(ChannelHandlerContext ctx) throws Exception {
		drain();
	}



	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		this.ctx = ctx;
		inner.request(prefetch);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		if (!removed) {
			removed = true;

			inner.cancel();
		}
	}

	@Override
	public void operationComplete(ChannelFuture future) throws Exception {
		inner.request(limit);
	}

	@Override
	final public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
			throws Exception {
		if(log.isTraceEnabled()){
			log.trace("User event {}", evt);
		}
		ctx.fireUserEventTriggered(evt);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {
		if (log.isDebugEnabled()) {
			log.debug("Writing object {}", msg);
		}

		if (!pendingWriteOffer.test(promise, msg)) {
			promise.setFailure(new IllegalStateException("Send Queue full?!"));
		}
	}

	ChannelFuture doWrite(Object msg, ChannelPromise promise, PublisherSender inner) {
		if (inner == null && pendingWrites.isEmpty()) {
			pendingBytes = 0L;
			return ctx.writeAndFlush(msg, promise);
		}
		else {
			if(msg instanceof ByteBuf){
				pendingBytes += ((ByteBuf)msg).readableBytes();
			}
			else if(msg instanceof ByteBufHolder){
				pendingBytes += ((ByteBufHolder)msg).content().readableBytes();
			}
			else if(msg instanceof FileRegion){
				pendingBytes += ((FileRegion)msg).count();
			}
			log.info("size = {}", pendingBytes);
			return ctx.write(msg, promise);
		}
	}

	@SuppressWarnings("unchecked")
	void drain() {
		if (WIP.getAndIncrement(this) == 0) {

			for (; ; ) {
				if (removed) {
					return;
				}

				if (innerActive || !ctx.channel()
				                       .isWritable()) {
					if (WIP.decrementAndGet(this) == 0) {
						break;
					}
					continue;
				}

				ChannelPromise promise;

				try {
					promise = (ChannelPromise) pendingWrites.poll();
				}
				catch (Throwable e) {
					ctx.fireExceptionCaught(e);
					return;
				}

				boolean empty = promise == null;

				if (empty) {
					if (WIP.decrementAndGet(this) == 0) {
						break;
					}
					continue;
				}

				Object v = pendingWrites.poll();

				if (v instanceof Publisher) {
					Publisher<?> p = (Publisher<?>) v;

					if (p instanceof Callable) {
						@SuppressWarnings("unchecked") Callable<?> supplier =
								(Callable<?>) p;

						Object vr;

						try {
							vr = supplier.call();
						}
						catch (Throwable e) {
							promise.setFailure(e);
							continue;
						}

						if (vr == null) {
							promise.setSuccess();
							continue;
						}

						if (inner.unbounded) {
							doWrite(vr, promise, null);
						}
						else {
							innerActive = true;
							inner.promise = promise;
							inner.onSubscribe(Operators.scalarSubscription(inner, vr));
						}
					}
					else {
						innerActive = true;
						inner.promise = promise;
						p.subscribe(inner);
					}
				}
				else {
					doWrite(v, promise, null);
				}
			}
		}
	}

	//
	final ChannelOperations<?, ?> inbound() {
		return ctx.channel()
		          .attr(ChannelOperations.OPERATIONS_ATTRIBUTE_KEY)
		          .get();
	}

	static final class PublisherSender
			implements Subscriber<Object>, Subscription, ChannelFutureListener {

		final ChannelOperationsHandler parent;

		volatile Subscription missedSubscription;
		volatile long         missedRequested;
		volatile long         missedProduced;
		volatile int          wip;

		boolean        inactive;
		boolean        justFlushed;
		/**
		 * The current outstanding request amount.
		 */
		long           requested;
		boolean        unbounded;
		/**
		 * The current subscription which may null if no Subscriptions have been set.
		 */
		Subscription   actual;
		long           produced;
		ChannelPromise promise;
		ChannelFuture  lastWrite;

		PublisherSender(ChannelOperationsHandler parent) {
			this.parent = parent;
		}

		@Override
		public final void cancel() {
			if (!inactive) {
				inactive = true;

				drain();
			}
		}

		@Override
		public void onComplete() {
			long p = produced;
			ChannelFuture f = lastWrite;
			parent.innerActive = false;

			if (p != 0L) {
				produced = 0L;
				produced(p);
				parent.ctx.flush();
			}

			if (f != null) {
				f.addListener(this);
			}
			else {
				promise.setSuccess();
				parent.drain();
			}
		}

		@Override
		public void onError(Throwable t) {
			long p = produced;
			ChannelFuture f = lastWrite;
			parent.innerActive = false;

			if (p != 0L) {
				produced = 0L;
				produced(p);
				parent.ctx.flush();
			}

			if (f != null) {
				f.addListener(this);
			}
			else {
				promise.setFailure(t);
				parent.drain();
			}
		}

		@Override
		public void onNext(Object t) {
			produced++;

			lastWrite = parent.doWrite(t, parent.ctx.newPromise(), this);
			if (parent.ctx.channel()
			              .isWritable()) {
				request(1L);
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (inactive) {
				s.cancel();
				return;
			}

			Objects.requireNonNull(s);

			if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
				actual = s;

				long r = requested;

				if (WIP.decrementAndGet(this) != 0) {
					drainLoop();
				}

				if (r != 0L) {
					s.request(r);
				}

				return;
			}

			MISSED_SUBSCRIPTION.set(this, s);
			drain();
		}

		@Override
		public void operationComplete(ChannelFuture future) throws Exception {
			if (future.isSuccess()) {
				promise.setSuccess();
			}
			else {
				promise.setFailure(future.cause());
			}
			parent.drain();
		}

		@Override
		public final void request(long n) {
			if (Operators.validate(n)) {
				if (unbounded) {
					return;
				}
				if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
					long r = requested;

					if (r != Long.MAX_VALUE) {
						r = Operators.addCap(r, n);
						requested = r;
						if (r == Long.MAX_VALUE) {
							unbounded = true;
						}
					}
					Subscription a = actual;

					if (WIP.decrementAndGet(this) != 0) {
						drainLoop();
					}

					if (a != null) {
						a.request(n);
					}

					return;
				}

				Operators.getAndAddCap(MISSED_REQUESTED, this, n);

				drain();
			}
		}

		final void drain() {
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}
			drainLoop();
		}

		final void drainLoop() {
			int missed = 1;

			long requestAmount = 0L;
			Subscription requestTarget = null;

			for (; ; ) {

				Subscription ms = missedSubscription;

				if (ms != null) {
					ms = MISSED_SUBSCRIPTION.getAndSet(this, null);
				}

				long mr = missedRequested;
				if (mr != 0L) {
					mr = MISSED_REQUESTED.getAndSet(this, 0L);
				}

				long mp = missedProduced;
				if (mp != 0L) {
					mp = MISSED_PRODUCED.getAndSet(this, 0L);
				}

				Subscription a = actual;

				if (inactive) {
					if (a != null) {
						a.cancel();
						actual = null;
					}
					if (ms != null) {
						ms.cancel();
					}
				}
				else {
					long r = requested;
					if (r != Long.MAX_VALUE) {
						long u = Operators.addCap(r, mr);

						if (u != Long.MAX_VALUE) {
							long v = u - mp;
							if (v < 0L) {
								Operators.reportMoreProduced();
								v = 0;
							}
							r = v;
						}
						else {
							r = u;
						}
						requested = r;
					}

					if (ms != null) {
						actual = ms;
						if (r != 0L) {
							requestAmount = Operators.addCap(requestAmount, r);
							requestTarget = ms;
						}
					}
					else if (mr != 0L && a != null) {
						requestAmount = Operators.addCap(requestAmount, mr);
						requestTarget = a;
					}
				}

				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					if (requestAmount != 0L) {
						requestTarget.request(requestAmount);
					}
					return;
				}
			}
		}

		final void produced(long n) {
			if (unbounded) {
				return;
			}
			if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
				long r = requested;

				if (r != Long.MAX_VALUE) {
					long u = r - n;
					if (u < 0L) {
						Operators.reportMoreProduced();
						u = 0;
					}
					requested = u;
				}
				else {
					unbounded = true;
				}

				if (WIP.decrementAndGet(this) == 0) {
					return;
				}

				drainLoop();

				return;
			}

			Operators.getAndAddCap(MISSED_PRODUCED, this, n);

			drain();
		}

		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<PublisherSender, Subscription>
				                                                MISSED_SUBSCRIPTION =
				AtomicReferenceFieldUpdater.newUpdater(PublisherSender.class,
						Subscription.class,
						"missedSubscription");
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<PublisherSender>    MISSED_REQUESTED    =
				AtomicLongFieldUpdater.newUpdater(PublisherSender.class,
						"missedRequested");
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<PublisherSender>    MISSED_PRODUCED     =
				AtomicLongFieldUpdater.newUpdater(PublisherSender.class,
						"missedProduced");
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<PublisherSender> WIP                 =
				AtomicIntegerFieldUpdater.newUpdater(PublisherSender.class, "wip");
	}

	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<ChannelOperationsHandler> WIP =
			AtomicIntegerFieldUpdater.newUpdater(ChannelOperationsHandler.class, "wip");
	static final Logger                                              log =
			Loggers.getLogger(ChannelOperationsHandler.class);

	static final BiConsumer<?, ? super ByteBuf> NOOP_ENCODER = (a, b) -> {
	};

}
