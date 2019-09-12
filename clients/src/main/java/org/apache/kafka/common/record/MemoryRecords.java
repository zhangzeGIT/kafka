/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.common.record;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.utils.AbstractIterator;

/**
 * A {@link Records} implementation backed by a ByteBuffer.
 */
public class MemoryRecords implements Records {

    private final static int WRITE_LIMIT_FOR_READABLE_ONLY = -1;

    // the compressor used for appends-only

    // 压缩器
    private final Compressor compressor;

    // the write limit for writable buffer, which may be smaller than the buffer capacity

    // buffer字段最多可以写入多少个字节的数据
    private final int writeLimit;

    // the capacity of the initial buffer, which is only used for de-allocation of writable records
    private final int initialCapacity;

    // the underlying buffer used for read; while the records are still writable it is null

    // 保存消息数据的Java NIO ByteBuffer
    private ByteBuffer buffer;

    // indicate if the memory records is writable or not (i.e. used for appends or read-only)

    // 此MemoryRecords对象是只读模式，还是可写模式，发送之前，会将其设置成只读模式
    private boolean writable;

    // Construct a writable memory records
    private MemoryRecords(ByteBuffer buffer, CompressionType type, boolean writable, int writeLimit) {
        this.writable = writable;
        this.writeLimit = writeLimit;
        this.initialCapacity = buffer.capacity();
        if (this.writable) {
            this.buffer = null;
            this.compressor = new Compressor(buffer, type);
        } else {
            this.buffer = buffer;
            this.compressor = null;
        }
    }

    public static MemoryRecords emptyRecords(ByteBuffer buffer, CompressionType type, int writeLimit) {
        return new MemoryRecords(buffer, type, true, writeLimit);
    }

    public static MemoryRecords emptyRecords(ByteBuffer buffer, CompressionType type) {
        // use the buffer capacity as the default write limit
        return emptyRecords(buffer, type, buffer.capacity());
    }

    public static MemoryRecords readableRecords(ByteBuffer buffer) {
        return new MemoryRecords(buffer, CompressionType.NONE, false, WRITE_LIMIT_FOR_READABLE_ONLY);
    }

    /**
     * Append the given record and offset to the buffer
     */
    public void append(long offset, Record record) {
        // 是否为可写模式
        if (!writable)
            throw new IllegalStateException("Memory records is not writable");

        int size = record.size();
        // 调用put*方法
        compressor.putLong(offset);
        compressor.putInt(size);
        compressor.put(record.buffer());
        compressor.recordWritten(size + Records.LOG_OVERHEAD);
        record.buffer().rewind();
    }

    /**
     * Append a new record and offset to the buffer
     * @return crc of the record
     */
    public long append(long offset, long timestamp, byte[] key, byte[] value) {
        if (!writable)
            throw new IllegalStateException("Memory records is not writable");

        int size = Record.recordSize(key, value);
        compressor.putLong(offset);
        compressor.putInt(size);
        long crc = compressor.putRecord(timestamp, key, value);
        compressor.recordWritten(size + Records.LOG_OVERHEAD);
        return crc;
    }

    /**
     * Check if we have room for a new record containing the given key/value pair
     *
     * Note that the return value is based on the estimate of the bytes written to the compressor, which may not be
     * accurate if compression is really used. When this happens, the following append may cause dynamic buffer
     * re-allocation in the underlying byte buffer stream.
     *
     * There is an exceptional case when appending a single message whose size is larger than the batch size, the
     * capacity will be the message size which is larger than the write limit, i.e. the batch size. In this case
     * the checking should be based on the capacity of the initialized buffer rather than the write limit in order
     * to accept this single record.
     * 估算MemoryRecords剩余空间是否足够写入指定的数据，可能导致扩容
     */
    public boolean hasRoomFor(byte[] key, byte[] value) {
        if (!this.writable)
            return false;

        return this.compressor.numRecordsWritten() == 0 ?
            this.initialCapacity >= Records.LOG_OVERHEAD + Record.recordSize(key, value) :
            this.writeLimit >= this.compressor.estimatedBytesWritten() + Records.LOG_OVERHEAD + Record.recordSize(key, value);
    }

    public boolean isFull() {
        return !this.writable || this.writeLimit <= this.compressor.estimatedBytesWritten();
    }

    /**
     * Close this batch for no more appends
     */
    public void close() {
        if (writable) {
            // close the compressor to fill-in wrapper message metadata if necessary
            compressor.close();

            // flip the underlying buffer to be ready for reads
            buffer = compressor.buffer();
            buffer.flip();

            // reset the writable flag
            writable = false;
        }
    }

    /**
     * The size of this record set
     */
    public int sizeInBytes() {
        if (writable) {
            return compressor.buffer().position();
        } else {
            return buffer.limit();
        }
    }

    /**
     * The compression rate of this record set
     */
    public double compressionRate() {
        if (compressor == null)
            return 1.0;
        else
            return compressor.compressionRate();
    }

    /**
     * Return the capacity of the initial buffer, for writable records
     * it may be different from the current buffer's capacity
     */
    public int initialCapacity() {
        return this.initialCapacity;
    }

    /**
     * Get the byte buffer that backs this records instance for reading
     */
    public ByteBuffer buffer() {
        if (writable)
            throw new IllegalStateException("The memory records must not be writable any more before getting its underlying buffer");

        return buffer.duplicate();
    }

    @Override
    public Iterator<LogEntry> iterator() {
        if (writable) {
            // flip on a duplicate buffer for reading
            return new RecordsIterator((ByteBuffer) this.buffer.duplicate().flip(), false);
        } else {
            // do not need to flip for non-writable buffer
            return new RecordsIterator(this.buffer.duplicate(), false);
        }
    }
    
    @Override
    public String toString() {
        Iterator<LogEntry> iter = iterator();
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        while (iter.hasNext()) {
            LogEntry entry = iter.next();
            builder.append('(');
            builder.append("offset=");
            builder.append(entry.offset());
            builder.append(",");
            builder.append("record=");
            builder.append(entry.record());
            builder.append(")");
        }
        builder.append(']');
        return builder.toString();
    }

    /** Visible for testing */
    public boolean isWritable() {
        return writable;
    }

    public static class RecordsIterator extends AbstractIterator<LogEntry> {
        // 指向MemoryRecords的buffer字段，消息可以是压缩的，也可以是未压缩的
        private final ByteBuffer buffer;
        // 读取buffer的输入流，如果迭代压缩消息，则是对应的解压缩输入流
        private final DataInputStream stream;
        // 压缩类型
        private final CompressionType type;
        // 为true，数据时非压缩的，false时，是深层迭代，，迭代嵌套的压缩消息
        private final boolean shallow;
        // 迭代压缩消息的Inner Iterator
        private RecordsIterator innerIter;

        // The variables for inner iterator
        // Inner Iterator需要迭代的压缩集合，封装了消息及其offset
        // Outer Iterator这个字段为空
        private final ArrayDeque<LogEntry> logEntries;
        // 在Inner Iterator迭代压缩消息时使用，用于记录压缩消息中第一个消息offset
        // Outer Iterator这个字段为-1
        private final long absoluteBaseOffset;

        /**
         * 提供给外部的API，用于创建outer iterator
         */
        public RecordsIterator(ByteBuffer buffer, boolean shallow) {
            // 外层消息为非压缩
            this.type = CompressionType.NONE;
            // 指向MemoryRecords.buffer字段
            this.buffer = buffer;
            // 标记是否为深层迭代器
            this.shallow = shallow;
            // 输入流
            this.stream = new DataInputStream(new ByteBufferInputStream(buffer));
            this.logEntries = null;
            this.absoluteBaseOffset = -1;
        }

        // Private constructor for inner iterator.
        private RecordsIterator(LogEntry entry) {
            // 指向内层压缩消息的压缩类型
            this.type = entry.record().compressionType();
            this.buffer = entry.record().value();
            this.shallow = true;
            // 创建指定压缩类型的输入流
            this.stream = Compressor.wrapForInput(new ByteBufferInputStream(this.buffer), type, entry.record().magic());
            // 外层的offset
            long wrapperRecordOffset = entry.offset();
            // If relative offset is used, we need to decompress the entire message first to compute
            // the absolute offset.
            if (entry.record().magic() > Record.MAGIC_VALUE_V0) {
                this.logEntries = new ArrayDeque<>();
                long wrapperRecordTimestamp = entry.record().timestamp();
                // 将内层消息全部解压出来并添加到logEntries集合中
                while (true) {
                    try {
                        // 对于内层消息，getNextEntryFromStream方法是读取并解压缩消息
                        // 对于外层消息或非压缩消息，则仅仅是读取消息
                        LogEntry logEntry = getNextEntryFromStream();
                        Record recordWithTimestamp = new Record(logEntry.record().buffer(),
                                                                wrapperRecordTimestamp,
                                                                entry.record().timestampType());
                        logEntries.add(new LogEntry(logEntry.offset(), recordWithTimestamp));
                    } catch (EOFException e) {
                        break;
                    } catch (IOException e) {
                        throw new KafkaException(e);
                    }
                }
                // 计算absoluteBaseOffset
                this.absoluteBaseOffset = wrapperRecordOffset - logEntries.getLast().offset();
            } else {
                this.logEntries = null;
                this.absoluteBaseOffset = -1;
            }

        }

        /*
         * Read the next record from the buffer.
         * 
         * Note that in the compressed message set, each message value size is set as the size of the un-compressed
         * version of the message value, so when we do de-compression allocating an array of the specified size for
         * reading compressed value data is sufficient.
         */
        // 迭代的过程
        @Override
        protected LogEntry makeNext() {
            // 检测当前深层迭代是否已经完成，或是深层迭代还未开始
            if (innerDone()) {
                try {
                    // 获取消息
                    LogEntry entry = getNextEntry();
                    // No more record to return.
                    // 获取不到消息，调用allDone结束迭代
                    if (entry == null)
                        return allDone();

                    // Convert offset to absolute offset if needed.
                    // 在Inner Iterator中计算每个消息的absoluteOffset
                    if (absoluteBaseOffset >= 0) {
                        long absoluteOffset = absoluteBaseOffset + entry.offset();
                        entry = new LogEntry(absoluteOffset, entry.record());
                    }

                    // decide whether to go shallow or deep iteration if it is compressed
                    // 根据压缩类型和shallow参数决定是否创建Inner Iterator
                    CompressionType compression = entry.record().compressionType();
                    if (compression == CompressionType.NONE || shallow) {
                        return entry;
                    } else {
                        // init the inner iterator with the value payload of the message,
                        // which will de-compress the payload to a set of messages;
                        // since we assume nested compression is not allowed, the deep iterator
                        // would not try to further decompress underlying messages
                        // There will be at least one element in the inner iterator, so we don't
                        // need to call hasNext() here.

                        // 创建Inner Iterator，每迭代一个外层消息，创建一个Inner Iterator
                        innerIter = new RecordsIterator(entry);
                        // 迭代内层消息
                        return innerIter.next();
                    }
                } catch (EOFException e) {
                    return allDone();
                } catch (IOException e) {
                    throw new KafkaException(e);
                }
            } else {
                return innerIter.next();
            }
        }

        private LogEntry getNextEntry() throws IOException {
            if (logEntries != null)
                // 从logEntries队列中获取LogEntry
                return getNextEntryFromEntryList();
            else
                // 从buffer中获取LogEntry
                return getNextEntryFromStream();
        }

        private LogEntry getNextEntryFromEntryList() {
            return logEntries.isEmpty() ? null : logEntries.remove();
        }


        private LogEntry getNextEntryFromStream() throws IOException {
            // read the offset
            long offset = stream.readLong();
            // read record size
            // 读取消息长度
            int size = stream.readInt();
            if (size < 0)
                throw new IllegalStateException("Record with size " + size);
            // read the record, if compression is used we cannot depend on size
            // and hence has to do extra copy
            ByteBuffer rec;
            // 未压缩消息处理
            if (type == CompressionType.NONE) {
                // rec是buffer的分片
                rec = buffer.slice();
                int newPos = buffer.position() + size;
                if (newPos > buffer.limit())
                    return null;
                // 修正buffer的position
                buffer.position(newPos);
                rec.limit(size);
            } else {
                // 处理压缩消息
                byte[] recordBuffer = new byte[size];
                // 从stream中读取消息，此过程会解压消息
                stream.readFully(recordBuffer, 0, size);
                rec = ByteBuffer.wrap(recordBuffer);
            }
            return new LogEntry(offset, new Record(rec));
        }

        private boolean innerDone() {
            return innerIter == null || !innerIter.hasNext();
        }
    }
}
