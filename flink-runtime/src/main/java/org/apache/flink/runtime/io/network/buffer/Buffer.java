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

package org.apache.flink.runtime.io.network.buffer;

import org.apache.flink.core.memory.MemorySegment;

import java.nio.ByteBuffer;

/**
 * Wrapper for pooled {@link MemorySegment} instances with reference counting.
 *
 * <p>This is similar to Netty's <tt>ByteBuf</tt> with some extensions and restricted to the methods
 * our use cases outside Netty handling use. In particular, we use two different indexes for read
 * and write operations, i.e. the <tt>reader</tt> and <tt>writer</tt> index, which specify three
 * regions inside the memory segment:
 * <pre>
 *     +-------------------+----------------+----------------+
 *     | discardable bytes | readable bytes | writable bytes |
 *     +-------------------+----------------+----------------+
 *     |                   |                |                |
 *     0      <=      readerIndex  <=  writerIndex   <=   size
 * </pre>
 *
 * <p>Our non-Netty usages of this <tt>Buffer</tt> class either rely on the underlying {@link
 * #getMemorySegment()} directly, or on {@link ByteBuffer} wrappers of this buffer which do not
 * modify either index, so the indices need to be updated manually via {@link #setReaderIndex(int)}
 * and {@link #setWriterIndex(int)}.
 */
public interface Buffer {

	/**
	 * Returns whether this buffer represents a buffer or an event.
	 *
	 * @return <tt>true</tt> if this is a real buffer, <tt>false</tt> if this is an event
	 */
	boolean isBuffer();

	/**
	 * Tags this buffer to represent an event.
	 */
	void tagAsEvent();

	/**
	 * Returns the underlying memory segment.
	 *
	 * @return the memory segment backing this buffer
	 */
	MemorySegment getMemorySegment();

	/**
	 * Releases this buffer once, i.e. reduces the reference count and recycles the buffer if the
	 * reference count reaches <tt>0</tt>.
	 *
	 * @see #retain()
	 */
	void recycle();

	/**
	 * Returns whether this buffer has been recycled or not.
	 *
	 * @return <tt>true</tt> if already recycled, <tt>false</tt> otherwise
	 */
	boolean isRecycled();

	/**
	 * Retains this buffer for further use, increasing the reference counter by <tt>1</tt>.
	 *
	 * @return <tt>this</tt> instance (for chained calls)
	 *
	 * @see #recycle()
	 */
	Buffer retain();

	/**
	 * Returns the size of the buffer, i.e. the capacity of the underlying {@link MemorySegment}.
	 *
	 * @return size of the buffer
	 */
	int getSize();

	/**
	 * Returns the <tt>reader index</tt> of this buffer.
	 *
	 * <p>This is where readable (unconsumed) bytes start in the backing memory segment.
	 *
	 * @return reader index (from 0 (inclusive) to the size of the backing {@link MemorySegment}
	 * (inclusive))
	 */
	int getReaderIndex();

	/**
	 * Sets the <tt>reader index</tt> of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 * 		if the index is less than <tt>0</tt> or greater than {@link #getWriterIndex()}
	 */
	void setReaderIndex(int readerIndex) throws IndexOutOfBoundsException;

	/**
	 * Returns the <tt>writer index</tt> of this buffer.
	 *
	 * <p>This is where writable bytes start in the backing memory segment.
	 *
	 * @return writer index (from 0 (inclusive) to the size of the backing {@link MemorySegment}
	 * (inclusive))
	 */
	int getWriterIndex();

	/**
	 * Sets the <tt>writer index</tt> of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 * 		if the index is less than {@link #getReaderIndex()} or greater than {@link #getSize()}
	 */
	void setWriterIndex(int writerIndex);

	/**
	 * Gets a new {@link ByteBuffer} instance wrapping this buffer's readable bytes, i.e. between
	 * {@link #getReaderIndex()} and {@link #getWriterIndex()}.
	 *
	 * <p>Please note that neither index is updated by the returned buffer.
	 *
	 * @return byte buffer sharing the contents of the underlying memory segment
	 */
	ByteBuffer getNioBufferReadable();

	/**
	 * Gets a new {@link ByteBuffer} instance wrapping this buffer's bytes.
	 *
	 * <p>Please note that neither <tt>read</tt> nor <tt>write</tt> index are updated by the
	 * returned buffer.
	 *
	 * @return byte buffer sharing the contents of the underlying memory segment
	 *
	 * @throws IndexOutOfBoundsException
	 * 		if the indexes are not without the buffer's bounds
	 * @see #getNioBufferReadable()
	 */
	ByteBuffer getNioBuffer(int index, int length) throws IndexOutOfBoundsException;
}
