/*
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

package org.apache.flink.runtime.io.network.netty;

import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.NetworkBuffer;
import org.apache.flink.runtime.io.network.partition.BufferAvailabilityListener;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.ResultPartitionProvider;
import org.apache.flink.runtime.io.network.partition.ResultSubpartitionView;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannelID;
import org.apache.flink.runtime.io.network.partition.consumer.LocalInputChannel;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Simple wrapper for the partition readerQueue iterator, which increments a
 * sequence number for each returned buffer and remembers the receiver ID.
 *
 * <p>It also keeps track of available buffers and notifies the outbound
 * handler about non-emptiness, similar to the {@link LocalInputChannel}.
 */
class SequenceNumberingViewReader implements BufferAvailabilityListener {

	private final Object requestLock = new Object();

	private final InputChannelID receiverId;

	private final AtomicLong numBuffersAvailable = new AtomicLong();

	private final PartitionRequestQueue requestQueue;

	private volatile ResultSubpartitionView subpartitionView;

	private int sequenceNumber = -1;

	SequenceNumberingViewReader(InputChannelID receiverId, PartitionRequestQueue requestQueue) {
		this.receiverId = receiverId;
		this.requestQueue = requestQueue;
	}

	void requestSubpartitionView(
		ResultPartitionProvider partitionProvider,
		ResultPartitionID resultPartitionId,
		int subPartitionIndex) throws IOException {

		synchronized (requestLock) {
			if (subpartitionView == null) {
				// This this call can trigger a notification we have to
				// schedule a separate task at the event loop that will
				// start consuming this. Otherwise the reference to the
				// view cannot be available in getNextBuffer().
				this.subpartitionView = partitionProvider.createSubpartitionView(
					resultPartitionId,
					subPartitionIndex,
					this);
			} else {
				throw new IllegalStateException("Subpartition already requested");
			}
		}
	}

	InputChannelID getReceiverId() {
		return receiverId;
	}

	int getSequenceNumber() {
		return sequenceNumber;
	}

	public BufferAndAvailability getNextBuffer() throws IOException, InterruptedException {
		NetworkBuffer next = (NetworkBuffer) subpartitionView.getNextBuffer();
		if (next != null) {
			long remaining = numBuffersAvailable.decrementAndGet();

			// define the part of the buffer that is ready to send
			int readerIndex = next.readerIndex();
			int readableBytes = next.readableBytes();
			if (readableBytes > 0) {
				// we got a new instance of a previously sent buffer with no new data
				// -> this should not be sent and therefore, we do not increase the sequenceNumber
				sequenceNumber++;
			}

			if (remaining >= 0) {
				return new BufferAndAvailability(next, readerIndex, readableBytes, remaining > 0);
			} else {
				throw new IllegalStateException("no buffer available");
			}
		} else {
			return null;
		}
	}

	public void notifySubpartitionConsumed() throws IOException {
		subpartitionView.notifySubpartitionConsumed();
	}

	public boolean isReleased() {
		return subpartitionView.isReleased();
	}

	public Throwable getFailureCause() {
		return subpartitionView.getFailureCause();
	}

	public void releaseAllResources() throws IOException {
		subpartitionView.releaseAllResources();
	}

	@Override
	public void notifyBuffersAvailable(long numBuffers) {
		// if this request made the channel non-empty, notify the input gate
		if (numBuffers > 0 && numBuffersAvailable.getAndAdd(numBuffers) == 0) {
			requestQueue.notifyReaderNonEmpty(this);
		}
	}

	@Override
	public String toString() {
		return "SequenceNumberingViewReader{" +
			"requestLock=" + requestLock +
			", receiverId=" + receiverId +
			", numBuffersAvailable=" + numBuffersAvailable.get() +
			", sequenceNumber=" + sequenceNumber +
			'}';
	}

	// ------------------------------------------------------------------------

	/**
	 * A combination of a {@link Buffer} and a flag indicating availability of further buffers.
	 */
	public static final class BufferAndAvailability {

		private final NetworkBuffer buffer;
		private final int readerIndex;
		private final int readableBytes;
		private final boolean moreAvailable;

		public BufferAndAvailability(
				NetworkBuffer buffer, int readerIndex, int readableBytes, boolean moreAvailable) {
			this.buffer = checkNotNull(buffer);
			this.readerIndex = readerIndex;
			this.readableBytes = readableBytes;
			this.moreAvailable = moreAvailable;
		}

		public NetworkBuffer buffer() {
			return buffer;
		}

		public int readerIndex() {
			return readerIndex;
		}

		public int readableBytes() {
			return readableBytes;
		}

		public boolean moreAvailable() {
			return moreAvailable;
		}
	}
}
