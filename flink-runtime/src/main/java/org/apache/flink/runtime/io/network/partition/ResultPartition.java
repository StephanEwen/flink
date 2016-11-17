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

import org.apache.flink.api.common.JobID;
import org.apache.flink.runtime.executiongraph.IntermediateResultPartition;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.disk.iomanager.IOManager.IOMode;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferPool;
import org.apache.flink.runtime.io.network.buffer.BufferPoolOwner;
import org.apache.flink.runtime.io.network.buffer.BufferProvider;
import org.apache.flink.runtime.io.network.partition.consumer.LocalInputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.taskmanager.TaskManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkElementIndex;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * A result partition for data produced by a single task.
 *
 * <p> This class is the runtime part of a logical {@link IntermediateResultPartition}. Essentially,
 * a result partition is a collection of {@link Buffer} instances. The buffers are organized in one
 * or more {@link ResultSubpartition} instances, which further partition the data depending on the
 * number of consuming tasks and the data {@link DistributionPattern}.
 *
 * <p> Tasks, which consume a result partition have to request one of its subpartitions. The request
 * happens either remotely (see {@link RemoteInputChannel}) or locally (see {@link LocalInputChannel})
 *
 * <h2>Life-cycle</h2>
 *
 * The life-cycle of each result partition has three (possibly overlapping) phases:
 * <ol>
 * <li><strong>Produce</strong>: </li>
 * <li><strong>Consume</strong>: </li>
 * <li><strong>Release</strong>: </li>
 * </ol>
 *
 * <h2>Lazy deployment and updates of consuming tasks</h2>
 *
 * Before a consuming task can request the result, it has to be deployed. The time of deployment
 * depends on the PIPELINED vs. BLOCKING characteristic of the result partition. With pipelined
 * results, receivers are deployed as soon as the first buffer is added to the result partition.
 * With blocking results on the other hand, receivers are deployed after the partition is finished.
 *
 * <h2>Buffer management</h2>
 *
 * <h2>State management</h2>
 */
public class ResultPartition implements BufferPoolOwner {

	private static final Logger LOG = LoggerFactory.getLogger(ResultPartition.class);
	
	private final String owningTaskName;

	private final JobID jobId;

	private final ResultPartitionID partitionId;

	/** Type of this partition. Defines the concrete subpartition implementation to use. */
	private final ResultPartitionType partitionType;

	/** The subpartitions of this partition. At least one. */
	private final ResultSubpartition[] subpartitions;

	private final ResultPartitionManager partitionManager;

	private final ResultPartitionConsumableNotifier partitionConsumableNotifier;

	private final boolean sendScheduleOrUpdateConsumersMessage;

	// - Runtime state --------------------------------------------------------

	private final AtomicBoolean isReleased = new AtomicBoolean();

	/**
	 * The total number of references to subpartitions of this result. The result partition can be
	 * safely released, iff the reference count is zero. A reference count of -1 denotes that the
	 * result partition has been released.
	 */
	private final AtomicInteger pendingReferences = new AtomicInteger();

	private BufferPool bufferPool;

	private boolean hasNotifiedPipelinedConsumers;

	private boolean isFinished;

	private volatile Throwable cause;

	// - Statistics ----------------------------------------------------------

	/** The total number of buffers (both data and event buffers) */
	private int totalNumberOfBuffers;

	/** The total number of bytes (both data and event buffers) */
	private long totalNumberOfBytes;

	public ResultPartition(
		String owningTaskName,
		JobID jobId,
		ResultPartitionID partitionId,
		ResultPartitionType partitionType,
		int numberOfSubpartitions,
		ResultPartitionManager partitionManager,
		ResultPartitionConsumableNotifier partitionConsumableNotifier,
		IOManager ioManager,
		IOMode defaultIoMode,
		boolean sendScheduleOrUpdateConsumersMessage,
		int pipelinedBoundedQueueLength) {

		this.owningTaskName = checkNotNull(owningTaskName);
		this.jobId = checkNotNull(jobId);
		this.partitionId = checkNotNull(partitionId);
		this.partitionType = checkNotNull(partitionType);
		this.subpartitions = new ResultSubpartition[numberOfSubpartitions];
		this.partitionManager = checkNotNull(partitionManager);
		this.partitionConsumableNotifier = checkNotNull(partitionConsumableNotifier);
		this.sendScheduleOrUpdateConsumersMessage = sendScheduleOrUpdateConsumersMessage;

		// Create the subpartitions.
		switch (partitionType) {
			case BLOCKING:
				for (int i = 0; i < subpartitions.length; i++) {
					subpartitions[i] = new SpillableSubpartition(
							i, this, ioManager, defaultIoMode);
				}

				break;

			case PIPELINED:
				for (int i = 0; i < subpartitions.length; i++) {
					// Regular pipelined partitions are always unbounded.
					subpartitions[i] = new PipelinedSubpartition(i, this, 0);
				}

				break;

			case PIPELINED_BOUNDED:
				for (int i = 0; i < subpartitions.length; i++) {
					subpartitions[i] = new PipelinedSubpartition(i, this, pipelinedBoundedQueueLength);
				}

				break;

			default:
				throw new IllegalArgumentException("Unsupported result partition type.");
		}

		// Initially, partitions should be consumed once before release.
		pin();

		LOG.debug("{}: Initialized {}", owningTaskName, this);
	}

	/**
	 * Registers a buffer pool with this result partition.
	 * <p>
	 * There is one pool for each result partition, which is shared by all its sub partitions.
	 * <p>
	 * The pool is registered with the partition *after* it as been constructed in order to conform
	 * to the life-cycle of task registrations in the {@link TaskManager}.
	 */
	public void registerBufferPool(BufferPool bufferPool) {
		checkArgument(bufferPool.getNumberOfRequiredMemorySegments() >= getNumberOfSubpartitions(),
				"Bug in result partition setup logic: Buffer pool has not enough guaranteed buffers for this result partition.");

		checkState(this.bufferPool == null, "Bug in result partition setup logic: Already registered buffer pool.");

		this.bufferPool = checkNotNull(bufferPool);

		// If the partition type is back pressure-free, we register with the buffer pool for
		// callbacks to release memory.
		if (!partitionType.hasBackPressure()) {
			bufferPool.setBufferPoolOwner(this);
		}
	}

	public JobID getJobId() {
		return jobId;
	}

	public ResultPartitionID getPartitionId() {
		return partitionId;
	}

	public int getNumberOfSubpartitions() {
		return subpartitions.length;
	}

	public BufferProvider getBufferProvider() {
		return bufferPool;
	}

	public int getTotalNumberOfBuffers() {
		return totalNumberOfBuffers;
	}

	public long getTotalNumberOfBytes() {
		return totalNumberOfBytes;
	}

	// VisibleForTesting
	public ResultSubpartition getSubPartition(int index) {
		return subpartitions[index];
	}

	// ------------------------------------------------------------------------

	/**
	 * Adds a buffer to the subpartition with the given index.
	 * 
	 * <p><b>IMPORTANT:</b> The ownership of the buffer will be handed over by
	 * this method - the caller should not try to hold onto the buffer any more, and not recycle it.
	 * 
	 * <p> For PIPELINED results, this will trigger the deployment of consuming tasks after the
	 * first buffer has been added.
	 */
	public void add(Buffer buffer, int subpartitionIndex, boolean backPressured) throws IOException, InterruptedException {
		boolean success = false;

		try {
			checkInProduceState();

			final ResultSubpartition subpartition = subpartitions[subpartitionIndex];

			synchronized (subpartition) {
				success = subpartition.add(buffer, backPressured);

				// Update statistics
				totalNumberOfBuffers++;
				totalNumberOfBytes += buffer.getSize();
			}
		}
		finally {
			if (success) {
				notifyPipelinedConsumers();
			}
			else {
				buffer.recycle();
			}
		}
	}

	/**
	 * Tries to add a buffer to the subpartition with the given index. 
	 *
	 * <p><b>IMPORTANT:</b> If the ownership of the buffer will be handed over by
	 * this method, then the caller should not try to hold onto the buffer any more, and not recycle it.
	 * 
	 * <p> For PIPELINED results, this will trigger the deployment of consuming tasks after the
	 * first buffer has been added.
	 * 
	 * @return True if the handover of the buffer was successful, false otherwise.
	 */
	public boolean addBufferIfCapacityAvailable(Buffer buffer, int subpartitionIndex) throws IOException {
		checkInProduceState();

		final ResultSubpartition subpartition = subpartitions[subpartitionIndex];

		boolean added;
		synchronized (subpartition) {
			if (added = subpartition.addIfCapacityAvailable(buffer)) {
				// Update statistics
				totalNumberOfBuffers++;
				totalNumberOfBytes += buffer.getSize();
			}
		}

		if (added) {
			notifyPipelinedConsumers();
		}
		return added;
	}

	/**
	 * Finishes the result partition.
	 *
	 * <p> After this operation, it is not possible to add further data to the result partition.
	 *
	 * <p> For BLOCKING results, this will trigger the deployment of consuming tasks.
	 */
	public void finish() throws IOException, InterruptedException {
		boolean success = false;

		try {
			checkInProduceState();

			for (ResultSubpartition subpartition : subpartitions) {
				synchronized (subpartition) {
					subpartition.finish();
				}
			}

			success = true;
		}
		finally {
			if (success) {
				isFinished = true;

				notifyPipelinedConsumers();
			}
		}
	}

	public void release() {
		release(null);
	}

	/**
	 * Releases the result partition.
	 */
	public void release(Throwable cause) {
		if (isReleased.compareAndSet(false, true)) {
			LOG.debug("{}: Releasing {}.", owningTaskName, this);

			// Set the error cause
			if (cause != null) {
				this.cause = cause;
			}

			// Release all subpartitions
			for (ResultSubpartition subpartition : subpartitions) {
				try {
					synchronized (subpartition) {
						subpartition.release();
					}
				}
				// Catch this in order to ensure that release is called on all subpartitions
				catch (Throwable t) {
					LOG.error("Error during release of result subpartition: " + t.getMessage(), t);
				}
			}
		}
	}

	public void destroyBufferPool() {
		if (bufferPool != null) {
			bufferPool.lazyDestroy();
		}
	}

	/**
	 * Returns the requested subpartition.
	 */
	public ResultSubpartitionView createSubpartitionView(int index, BufferProvider bufferProvider) throws IOException {
		int refCnt = pendingReferences.get();

		checkState(refCnt != -1, "Partition released.");
		checkState(refCnt > 0, "Partition not pinned.");

		checkElementIndex(index, subpartitions.length, "Subpartition not found.");

		ResultSubpartitionView readView = subpartitions[index].createReadView(bufferProvider);

		LOG.debug("Created {}", readView);

		return readView;
	}

	public Throwable getFailureCause() {
		return cause;
	}

	/**
	 * Releases buffers held by this result partition.
	 *
	 * <p> This is a callback from the buffer pool, which is registered for result partitions, which
	 * are back pressure-free.
	 */
	@Override
	public void releaseMemory(int toRelease) throws IOException {
		checkArgument(toRelease > 0);

		for (ResultSubpartition subpartition : subpartitions) {
			toRelease -= subpartition.releaseMemory();

			// Only release as much memory as needed
			if (toRelease <= 0) {
				break;
			}
		}
	}

	@Override
	public String toString() {
		return "ResultPartition " + partitionId.toString() + " [" + partitionType + ", "
				+ subpartitions.length + " subpartitions, "
				+ pendingReferences + " pending references]";
	}

	// ------------------------------------------------------------------------

	/**
	 * Pins the result partition.
	 *
	 * <p> The partition can only be released after each subpartition has been consumed once per pin
	 * operation.
	 */
	void pin() {
		while (true) {
			int refCnt = pendingReferences.get();

			if (refCnt >= 0) {
				if (pendingReferences.compareAndSet(refCnt, refCnt + subpartitions.length)) {
					break;
				}
			}
			else {
				throw new IllegalStateException("Released.");
			}
		}
	}

	/**
	 * Notification when a subpartition is released.
	 */
	void onConsumedSubpartition(int subpartitionIndex) {
		if (isReleased.get()) {
			return;
		}

		int refCnt = pendingReferences.decrementAndGet();

		if (refCnt == 0) {
			partitionManager.onConsumedPartition(this);
		}
		else if (refCnt < 0) {
			throw new IllegalStateException("All references released.");
		}

		LOG.debug("{}: Received release notification for subpartition {} (reference count now at: {}).",
				this, subpartitionIndex, pendingReferences);
	}

	ResultSubpartition[] getAllPartitions() {
		return subpartitions;
	}

	// ------------------------------------------------------------------------

	private void checkInProduceState() {
		checkState(!isFinished, "Partition already finished.");
	}

	/**
	 * Notifies pipelined consumers of this result partition once.
	 */
	private void notifyPipelinedConsumers() {
		if (sendScheduleOrUpdateConsumersMessage && !hasNotifiedPipelinedConsumers && partitionType.isPipelined()) {
			partitionConsumableNotifier.notifyPartitionConsumable(jobId, partitionId);

			hasNotifiedPipelinedConsumers = true;
		}
	}
}
