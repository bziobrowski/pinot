/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.segment.local.io.writer.impl;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.apache.pinot.segment.local.io.compression.ChunkCompressorFactory;
import org.apache.pinot.segment.spi.compression.ChunkCompressionType;
import org.apache.pinot.segment.spi.compression.ChunkCompressor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Base implementation for chunk-based raw (non-dictionary-encoded) forward index writer where each chunk contains fixed
 * number of docs.
 *
 * <p>The layout of the file is as follows:
 * <ul>
 *   <li>Header Section
 *   <ul>
 *     <li>File format version (int)</li>
 *     <li>Total number of chunks (int)</li>
 *     <li>Number of docs per chunk (int)</li>
 *     <li>Size of entry in bytes (int)</li>
 *     <li>Total number of docs (int)</li>
 *     <li>Compression type enum value (int)</li>
 *     <li>Start offset of data header (int)</li>
 *     <li>Data header (start offsets for all chunks)
 *     <ul>
 *       <li>For version 2, offset is stored as int</li>
 *       <li>For version 3 onwards, offset is stored as long</li>
 *     </ul>
 *     </li>
 *   </ul>
 *   </li>
 *   <li>Individual Chunks</li>
 * </ul>
 */
public abstract class BaseChunkForwardIndexWriter implements Closeable {
  private static final Logger LOGGER = LoggerFactory.getLogger(BaseChunkForwardIndexWriter.class);

  protected final FileChannel _dataFile;
  protected ByteBuffer _header;
  protected final ByteBuffer _chunkBuffer;
  protected final ByteBuffer _compressedBuffer;
  protected final ChunkCompressor _chunkCompressor;

  protected int _chunkSize;
  protected long _dataOffset;

  private final int _headerEntryChunkOffsetSize;

  /**
   * Constructor for the class.
   *
   * @param file Data file to write into
   * @param compressionType Type of compression
   * @param totalDocs Total docs to write
   * @param numDocsPerChunk Number of docs per data chunk
   * @param chunkSize Size of chunk
   * @param sizeOfEntry Size of entry (in bytes), max size for variable byte implementation.
   * @param version version of File
   * @param fixed if the data type is fixed width (required for version validation)
   * @throws IOException if the file isn't found or can't be mapped
   */
  protected BaseChunkForwardIndexWriter(File file, ChunkCompressionType compressionType, int totalDocs,
      int numDocsPerChunk, long chunkSize, int sizeOfEntry, int version, boolean fixed)
      throws IOException {
    Preconditions.checkArgument(version == 2 || version == 3 || (fixed && (version == 4 || version == 5)),
        "Illegal version: %s for %s bytes values", version, fixed ? "fixed" : "variable");
    Preconditions.checkArgument(chunkSize <= Integer.MAX_VALUE, "Chunk size limited to 2GB");
    _chunkSize = (int) chunkSize;
    _chunkCompressor = ChunkCompressorFactory.getCompressor(compressionType);
    _headerEntryChunkOffsetSize = version == 2 ? Integer.BYTES : Long.BYTES;
    _dataOffset = writeHeader(compressionType, totalDocs, numDocsPerChunk, sizeOfEntry, version);
    _chunkBuffer = ByteBuffer.allocateDirect(_chunkSize);
    int maxCompressedChunkSize = _chunkCompressor.maxCompressedSize(_chunkSize); // may exceed original chunk size
    _compressedBuffer = ByteBuffer.allocateDirect(maxCompressedChunkSize);
    _dataFile = new RandomAccessFile(file, "rw").getChannel();
  }

  @Override
  public void close()
      throws IOException {

    // Write the chunk if it is non-empty.
    if (_chunkBuffer.position() > 0) {
      writeChunk();
    }

    // Write the header and close the file.
    _header.flip();
    _dataFile.write(_header, 0);
    _dataFile.close();
    _chunkCompressor.close();
  }

  /**
   * Helper method to write header information.
   *
   * @param compressionType Compression type for the data
   * @param totalDocs Total number of records
   * @param numDocsPerChunk Number of documents per chunk
   * @param sizeOfEntry Size of each entry
   * @param version Version of file
   * @return Size of header
   */
  private int writeHeader(ChunkCompressionType compressionType, int totalDocs, int numDocsPerChunk, int sizeOfEntry,
      int version) {
    int numChunks = (totalDocs + numDocsPerChunk - 1) / numDocsPerChunk;
    int headerSize = (7 * Integer.BYTES) + (numChunks * _headerEntryChunkOffsetSize);

    _header = ByteBuffer.allocateDirect(headerSize);

    int offset = 0;
    _header.putInt(version);
    offset += Integer.BYTES;

    _header.putInt(numChunks);
    offset += Integer.BYTES;

    _header.putInt(numDocsPerChunk);
    offset += Integer.BYTES;

    _header.putInt(sizeOfEntry);
    offset += Integer.BYTES;

    // Write total number of docs.
    _header.putInt(totalDocs);
    offset += Integer.BYTES;

    // Write the compressor type
    _header.putInt(compressionType.getValue());
    offset += Integer.BYTES;

    // Start of chunk offsets.
    int dataHeaderStart = offset + Integer.BYTES;
    _header.putInt(dataHeaderStart);

    return headerSize;
  }

  /**
   * Helper method to compress and write the current chunk.
   * <ul>
   *   <li> Chunk header is of fixed size, so fills out any remaining offsets for partially filled chunks. </li>
   *   <li> Compresses (if required) and writes the chunk to the data file. </li>
   *   <li> Updates the header with the current chunks offset. </li>
   *   <li> Clears up the buffers, so that they can be reused. </li>
   * </ul>
   *
   */
  protected void writeChunk() {
    int sizeToWrite;
    _chunkBuffer.flip();

    try {
      sizeToWrite = _chunkCompressor.compress(_chunkBuffer, _compressedBuffer);
      _dataFile.write(_compressedBuffer, _dataOffset);
      _compressedBuffer.clear();
    } catch (IOException e) {
      LOGGER.error("Exception caught while compressing/writing data chunk", e);
      throw new RuntimeException(e);
    }

    if (_headerEntryChunkOffsetSize == Integer.BYTES) {
      Preconditions.checkState(_dataOffset <= Integer.MAX_VALUE, "Integer overflow detected. "
          + "Try to use raw version 3 or 4, reduce targetDocsPerChunk or targetMaxChunkSize");
      _header.putInt((int) _dataOffset);
    } else if (_headerEntryChunkOffsetSize == Long.BYTES) {
      _header.putLong(_dataOffset);
    }

    _dataOffset += sizeToWrite;
    _chunkBuffer.clear();
  }
}
