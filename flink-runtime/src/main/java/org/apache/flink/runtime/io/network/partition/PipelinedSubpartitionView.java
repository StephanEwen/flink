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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * View over a pipelined in-memory only subpartition.
 */
class PipelinedSubpartitionView implements ResultSubpartitionView {

	/** The subpartition this view belongs to. */
	private final PipelinedSubpartition parent;

	private final PipelinedAvailabilityListener availabilityListener;

	/** Flag indicating whether this view has been released. */
	private final AtomicBoolean isReleased;

	PipelinedSubpartitionView(PipelinedSubpartition parent, PipelinedAvailabilityListener listener) {
		this.parent = checkNotNull(parent);
		this.availabilityListener = checkNotNull(listener);
		this.isReleased = new AtomicBoolean();
	}

	@Override
	public Buffer getNextBuffer() {
		return parent.pollBuffer();
	}

	@Override
	public void notifyBuffersAvailable(int numBuffers) throws IOException {
		availabilityListener.notifyBuffersAvailable(numBuffers);
	}

	@Override
	public void notifySubpartitionConsumed() {
		releaseAllResources();
	}

	@Override
	public void releaseAllResources() {
		if (isReleased.compareAndSet(false, true)) {
			// The view doesn't hold any resources and the parent cannot be restarted. Therefore,
			// it's OK to notify about consumption as well.
			parent.onConsumedSubpartition();
		}
	}

	@Override
	public boolean isReleased() {
		return isReleased.get();
	}

	@Override
	public Throwable getFailureCause() {
		return parent.getFailureCause();
	}

	@Override
	public String toString() {
		return String.format("PipelinedSubpartitionView(index: %d) of ResultPartition %s",
				parent.index,
				parent.parent.getPartitionId());
	}
}
