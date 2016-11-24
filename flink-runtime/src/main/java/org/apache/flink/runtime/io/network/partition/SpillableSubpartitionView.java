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

package org.apache.flink.runtime.io.network.partition;

import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferProvider;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

class SpillableSubpartitionView implements ResultSubpartitionView {

	/** The subpartition this view belongs to. */
	private final SpillableSubpartition parent;

	/** The buffer provider to read buffers into (spilling case). */
	private final BufferProvider bufferProvider;

	/** The number of buffers in-memory at the subpartition. */
	private final int numberOfBuffers;

	private ResultSubpartitionView spilledView;

	private int currentQueuePosition;

	private long currentBytesRead;

	private final AtomicBoolean isReleased = new AtomicBoolean(false);

	public SpillableSubpartitionView(
			SpillableSubpartition parent,
			BufferProvider bufferProvider,
			int numberOfBuffers) {

		this.parent = checkNotNull(parent);
		this.bufferProvider = checkNotNull(bufferProvider);
		checkArgument(numberOfBuffers >= 0);
		this.numberOfBuffers = numberOfBuffers;
	}

	@Override
	public Buffer getNextBuffer() throws IOException, InterruptedException {
		return null;
//		if (isReleased.get()) {
//			return null;
//		}
//
//		// 1) In-memory
//		synchronized (parent.buffers) {
//			if (parent.isReleased()) {
//				return null;
//			}
//
//			if (parent.spillWriter == null) {
//				if (currentQueuePosition < numberOfBuffers) {
//					Buffer buffer = parent.buffers.get(currentQueuePosition);
//
//					buffer.retain();
//
//					// TODO Fix hard coding of 8 bytes for the header
//					currentBytesRead += buffer.getSize() + 8;
//					currentQueuePosition++;
//
//					return buffer;
//				}
//
//				return null;
//			}
//		}
//
//		// 2) Spilled
//		if (spilledView != null) {
//			return spilledView.getNextBuffer();
//		}
//
//		// 3) Spilling
//		// Make sure that all buffers are written before consuming them. We can't block here,
//		// because this might be called from an network I/O thread.
//		if (parent.spillWriter.getNumberOfOutstandingRequests() > 0) {
//			return null;
//		}
//
//		spilledView = new SpilledSubpartitionViewSyncIO(
//				parent,
//				bufferProvider.getMemorySegmentSize(),
//				parent.spillWriter.getChannelID(),
//				currentBytesRead);
//
//		return spilledView.getNextBuffer();
	}

	@Override
	public void notifyBuffersAvailable(int buffers) throws IOException {

	}

//	@Override
//	public boolean registerListener(NotificationListener listener) throws IOException {
//		if (spilledView == null) {
//			synchronized (parent.buffers) {
//				// Didn't spill yet, buffers should be in-memory
//				if (parent.spillWriter == null) {
//					return false;
//				}
//			}
//
//			// Spilling
//			if (parent.spillWriter.getNumberOfOutstandingRequests() > 0) {
//				return parent.spillWriter.registerAllRequestsProcessedListener(listener);
//			}
//
//			return false;
//		}
//
//		return spilledView.registerListener(listener);
//	}

	@Override
	public void notifySubpartitionConsumed() throws IOException {
		parent.onConsumedSubpartition();
	}

	@Override
	public void releaseAllResources() throws IOException {
		if (isReleased.compareAndSet(false, true)) {
			if (spilledView != null) {
				spilledView.releaseAllResources();
			}
		}
	}

	@Override
	public boolean isReleased() {
		return parent.isReleased() || isReleased.get();
	}

	@Override
	public Throwable getFailureCause() {
		return parent.getFailureCause();
	}

	@Override
	public String toString() {
		return String.format("SpillableSubpartitionView(index: %d) of ResultPartition %s",
				parent.index,
				parent.parent.getPartitionId());
	}
}
