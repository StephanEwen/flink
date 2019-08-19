/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.kafka.newsrc;

import org.apache.flink.api.connectors.source.SourceSplit;
import org.apache.kafka.common.TopicPartition;

import java.util.Optional;

/**
 * The SourceSplit class for Kafka. It contains the partition and offset.
 */
public class KafkaPartition implements SourceSplit {
	private final TopicPartition tp;
	private final long offset;
	private final long endOffset;
	private final int leaderEpoch;

	public KafkaPartition(TopicPartition tp, long offset, int leaderEpoch) {
		this(tp, offset, Long.MAX_VALUE, leaderEpoch);
	}

	public KafkaPartition(TopicPartition tp, long offset, long endOffset, int leaderEpoch) {
		this.tp = tp;
		this.offset = offset;
		this.endOffset = endOffset;
		this.leaderEpoch = leaderEpoch;
	}

	public TopicPartition topicPartition() {
		return tp;
	}

	public long offset() {
		return offset;
	}

	public long endOffset() {
		return endOffset;
	}

	public Optional<Integer> leaderEpoch() {
		return leaderEpoch < 0 ? Optional.empty() : Optional.of(leaderEpoch);
	}

	@Override
	public String splitId() {
		return tp.toString();
	}

	@Override
	public String toString() {
		return String.format("%s, offset=%d, endOffset=%d, leaderEpoch=%d", tp, offset, endOffset, leaderEpoch);
	}
}
