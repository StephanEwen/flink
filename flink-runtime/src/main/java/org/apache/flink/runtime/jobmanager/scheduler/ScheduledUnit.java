/**
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

package org.apache.flink.runtime.jobmanager.scheduler;

import org.apache.flink.runtime.executiongraph.ExecutionVertex2;
import org.apache.flink.runtime.jobgraph.JobVertexID;

public class ScheduledUnit {
	
	private final ExecutionVertex2 taskVertex;
	
	private final SlotSharingGroup sharingGroup;
	
	// --------------------------------------------------------------------------------------------
	
	public ScheduledUnit(ExecutionVertex2 taskVertex) {
		if (taskVertex == null) {
			throw new NullPointerException();
		}
		
		this.taskVertex = taskVertex;
		this.sharingGroup = null;
	}
	
	public ScheduledUnit(ExecutionVertex2 taskVertex, SlotSharingGroup sharingUnit) {
		if (taskVertex == null) {
			throw new NullPointerException();
		}
		
		this.taskVertex = taskVertex;
		this.sharingGroup = sharingUnit;
	}
	
	ScheduledUnit() {
		this.taskVertex = null;
		this.sharingGroup = null;
	}

	// --------------------------------------------------------------------------------------------
	
	public JobVertexID getJobVertexId() {
		return this.taskVertex.getJobvertexId();
	}
	
	public ExecutionVertex2 getTaskVertex() {
		return taskVertex;
	}
	
	public SlotSharingGroup getSlotSharingGroup() {
		return sharingGroup;
	}

	// --------------------------------------------------------------------------------------------
	
	@Override
	public String toString() {
		return "{vertex=" + taskVertex.getSimpleName() + ", sharingUnit=" + sharingGroup + '}';
	}
}
