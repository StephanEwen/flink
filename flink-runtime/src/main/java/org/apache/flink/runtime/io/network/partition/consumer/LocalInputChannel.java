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

package org.apache.flink.runtime.io.network.partition.consumer;

import org.apache.flink.runtime.io.network.partition.PipelinedAvailabilityListener;
import org.apache.flink.runtime.metrics.groups.IOMetricGroup;
import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.io.network.TaskEventDispatcher;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.partition.PartitionNotFoundException;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.ResultPartitionManager;
import org.apache.flink.runtime.io.network.partition.ResultSubpartitionView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Tuple2;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * An input channel, which requests a local subpartition.
 */
public class LocalInputChannel extends InputChannel implements PipelinedAvailabilityListener {

	private static final Logger LOG = LoggerFactory.getLogger(LocalInputChannel.class);

	// ------------------------------------------------------------------------

	private final Object requestReleaseLock = new Object();

	/** The local partition manager. */
	private final ResultPartitionManager partitionManager;

	/** Task event dispatcher for backwards events. */
	private final TaskEventDispatcher taskEventDispatcher;

	private final AtomicLong numBuffersAvailable;

	/** The consumed subpartition */
	private volatile ResultSubpartitionView subpartitionView;

	private volatile boolean isReleased;

	LocalInputChannel(
			SingleInputGate inputGate,
			int channelIndex,
			ResultPartitionID partitionId,
			ResultPartitionManager partitionManager,
			TaskEventDispatcher taskEventDispatcher,
			IOMetricGroup metrics) {

		this(inputGate, channelIndex, partitionId, partitionManager, taskEventDispatcher,
				new Tuple2<Integer, Integer>(0, 0), metrics);
	}

	LocalInputChannel(
			SingleInputGate inputGate,
			int channelIndex,
			ResultPartitionID partitionId,
			ResultPartitionManager partitionManager,
			TaskEventDispatcher taskEventDispatcher,
			Tuple2<Integer, Integer> initialAndMaxBackoff,
			IOMetricGroup metrics) {

		super(inputGate, channelIndex, partitionId, initialAndMaxBackoff, metrics.getNumBytesInLocalCounter());

		this.partitionManager = checkNotNull(partitionManager);
		this.taskEventDispatcher = checkNotNull(taskEventDispatcher);
		this.numBuffersAvailable = new AtomicLong();
	}

	// ------------------------------------------------------------------------
	// Consume
	// ------------------------------------------------------------------------

	@Override
	void requestSubpartition(int subpartitionIndex) throws IOException, InterruptedException {
		// The lock is required to request only once in the presence of retriggered requests.
		synchronized (requestReleaseLock) {
			checkState(!isReleased, "released");

			if (subpartitionView == null) {
				LOG.debug("{}: Requesting LOCAL subpartition {} of partition {}.",
						this, subpartitionIndex, partitionId);

				try {
					ResultSubpartitionView subpartitionView = partitionManager.createSubpartitionView(
							partitionId, subpartitionIndex, inputGate.getBufferProvider(), this);

					if (subpartitionView == null) {
						throw new IOException("Error requesting subpartition.");
					}

					// make the subpartition view visible
					this.subpartitionView = subpartitionView;

					// check if the channel was released in the meantime
					if (isReleased) {
						subpartitionView.releaseAllResources();
						this.subpartitionView = null;
					}
				}
				catch (PartitionNotFoundException notFound) {
					if (increaseBackoff()) {
						inputGate.retriggerPartitionRequest(partitionId.getPartitionId());
					}
					else {
						throw notFound;
					}
				}
			}
		}
	}

	/**
	 * Retriggers a subpartition request.
	 */
	void retriggerSubpartitionRequest(Timer timer, final int subpartitionIndex) {
		synchronized (requestReleaseLock) {
			checkState(subpartitionView == null, "already requested partition");

			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					try {
						requestSubpartition(subpartitionIndex);
					}
					catch (Throwable t) {
						setError(t);
					}
				}
			}, getCurrentBackoff());
		}
	}

	@Override
	BufferAndAvailability getNextBuffer() throws IOException, InterruptedException {
		checkError();
		checkState(subpartitionView != null, "Queried for a buffer before requesting the subpartition.");

		ResultSubpartitionView subpartitionView = this.subpartitionView;
		if (subpartitionView == null) {
			// this can happen if the request for the partition was triggered asynchronously
			// by the time trigger
			// would be good to avoid that, by guaranteeing that the requestPartition() and
			// getNextBuffer() always come from the same thread
			// we could do that by letting the timer insert a special "requesting channel" into the input gate's queue
			subpartitionView = checkAndWaitForSubpartitionView();
		}

		Buffer next = subpartitionView.getNextBuffer();
		long remaining = numBuffersAvailable.decrementAndGet();

		if (remaining >= 0) {
			numBytesIn.inc(next.getSize());
			return new BufferAndAvailability(next, remaining > 0);
		}
		else {
			throw new IllegalStateException("no buffer available");
		}
	}

	@Override
	public void notifyBuffersAvailable(long numBuffers) {
		// if this request made the channel non-empty, notify the input gate
		if (numBuffers > 0 && numBuffersAvailable.getAndAdd(numBuffers) == 0) {
			notifyChannelNonEmpty();
		}
	}

	private ResultSubpartitionView checkAndWaitForSubpartitionView() {
		// synchronizing on the request lock means this blocks until the asynchronous request
		// for the partition view has been completed
		// by then the subpartition view is visible or the channel is released
		synchronized (requestReleaseLock) {
			checkState(!isReleased, "released");
			checkState(subpartitionView != null, "Queried for a buffer before requesting the subpartition.");
			return subpartitionView;
		}
	}

	// ------------------------------------------------------------------------
	// Task events
	// ------------------------------------------------------------------------

	@Override
	void sendTaskEvent(TaskEvent event) throws IOException {
		checkError();
		checkState(subpartitionView != null, "Tried to send task event to producer before requesting the subpartition.");

		if (!taskEventDispatcher.publish(partitionId, event)) {
			throw new IOException("Error while publishing event " + event + " to producer. The producer could not be found.");
		}
	}

	// ------------------------------------------------------------------------
	// Life cycle
	// ------------------------------------------------------------------------

	@Override
	boolean isReleased() {
		return isReleased;
	}

	@Override
	void notifySubpartitionConsumed() throws IOException {
		if (subpartitionView != null) {
			subpartitionView.notifySubpartitionConsumed();
		}
	}

	/**
	 * Releases the look ahead {@link Buffer} instance and discards the queue
	 * iterator.
	 */
	@Override
	void releaseAllResources() throws IOException {
		synchronized (requestReleaseLock) {
			if (!isReleased) {
				isReleased = true;

				if (subpartitionView != null) {
					subpartitionView.releaseAllResources();
					subpartitionView = null;
				}
			}
		}
	}

	@Override
	public String toString() {
		return "LocalInputChannel [" + partitionId + "]";
	}
}
