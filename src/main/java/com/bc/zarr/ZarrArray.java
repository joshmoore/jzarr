/*
 *
 * MIT License
 *
 * Copyright (c) 2020. Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.bc.zarr;

import com.bc.zarr.chunk.ChunkReaderWriter;
import com.bc.zarr.storage.FileSystemStore;
import com.bc.zarr.storage.InMemoryStore;
import com.bc.zarr.storage.Store;
import com.bc.zarr.ucar.NetCDF_Util;
import com.bc.zarr.ucar.PartialDataCopier;
import ucar.ma2.Array;
import ucar.ma2.InvalidRangeException;

import java.io.*;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static com.bc.zarr.CompressorFactory.nullCompressor;
import static com.bc.zarr.ZarrConstants.FILENAME_DOT_ZARRAY;

public class ZarrArray {

    private final static boolean DEFAULT_NESTED;
    static {
        DEFAULT_NESTED = Boolean.parseBoolean(System.getProperty("jzarr.nested", "false"));
    }

    private final int[] _shape;
    private final int[] _chunks;
    private final ZarrPath relativePath;
    private final ChunkReaderWriter _chunkReaderWriter;
    private final Map<String, String> _chunkFilenames;
    private final DataType _dataType;
    private final Number _fillValue;
    private final Compressor _compressor;
    private final Store _store;
    private final ByteOrder _byteOrder;
    private final Boolean _nested;

    private ZarrArray(ZarrPath relativePath, int[] shape, int[] chunkShape, DataType dataType, ByteOrder order, Number fillValue, Compressor compressor, Store store, Boolean nested) {
        this.relativePath = relativePath;
        _shape = shape;
        _chunks = chunkShape;
        _dataType = dataType;
        _fillValue = fillValue;
        if (compressor == null) {
            _compressor = nullCompressor;
        } else {
            _compressor = compressor;
        }
        _store = store;
        _chunkReaderWriter = ChunkReaderWriter.create(_compressor, _dataType, order, _chunks, _fillValue, _store);
        _chunkFilenames = new HashMap<>();
        _byteOrder = order;
        _nested = nested;
    }

    private ZarrArray(ZarrPath relativePath, int[] shape, int[] chunkShape, DataType dataType, ByteOrder order, Number fillValue, Compressor compressor, Store store) {
        this(relativePath, shape, chunkShape, dataType, order, fillValue, compressor, store, null);
    }
    public static ZarrArray open(String path) throws IOException {
        return open(Paths.get(path));
    }

    public static ZarrArray open(Path fileSystemPath) throws IOException {
        return open(new FileSystemStore(fileSystemPath));
    }

    public static ZarrArray open(Store store) throws IOException {
        return open(new ZarrPath(""), store);
    }

    public static ZarrArray open(ZarrPath relativePath, Store store) throws IOException {
        final ZarrPath zarrHeaderPath = relativePath.resolve(FILENAME_DOT_ZARRAY);
        ZarrHeader header;

        try (final InputStream storageStream = store.getInputStream(zarrHeaderPath.storeKey)){
            if(storageStream == null) {
                throw new IOException("'" + FILENAME_DOT_ZARRAY + "' expected but is not readable or missing in store.");
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(storageStream))) {
                header = ZarrUtils.fromJson(reader, ZarrHeader.class);
            }
        }

        final int[] shape = header.getShape();
        final int[] chunks = header.getChunks();
        final DataType dataType = header.getRawDataType();
        final ByteOrder byteOrder = header.getByteOrder();
        final Number fillValue = header.getFill_value();
        Compressor compressor = header.getCompressor();
        if (compressor == null) {
            compressor = nullCompressor;
        }

        // Workaround: In Zarr V2 there is no way to know whether or not an array is
        // nested or flat. If the files were guaranteed to be local, we could check for _any_
        // file of the form \d.\d*, but since the chunks may be stored elsewhere, we're going
        // to loop through all possible chunks and check their existence in one of the two possible
        // locations. Once one is found, we will assume _all_ follow the same pattern.
        Boolean nested = header.getNested();
        if (nested == null) {

            // Loop through all dimensions
            int n = shape.length;
            int[] ptr = new int[n];
            long total = 1;
            for (int i : shape) {
                total *= i;
            }

            for (int ignore = 0; ignore < total; ignore++) { // essentially a while(true) for our known maximum.
                // This n-dim loops allows to walk through all possible values for all dimensions.
                for (boolean test : Arrays.asList(true, false)) {
                    final String chunkFilename = ZarrUtils.createChunkFilename(ptr, test);
                    final ZarrPath chunkFilePath = relativePath.resolve(chunkFilename);
                    try (final InputStream storageStream = store.getInputStream(chunkFilePath.storeKey)) {
                        if (storageStream != null) { // TODO: test available() ?
                            nested = test;
                            break;
                        }
                    }
                }
                if (nested != null) {
                    break;
                } else {
                    for(int j = 0; j < n; j++) {
                        ptr[j]++;
                        if(ptr[j] < shape[j]) {
                            break;
                        } else {
                            ptr[j] = 0;
                        }
                    }
                }
            }

            if (nested == null) {
                // In this case, *no* chunk was found. Something is almost certainly wrong.
                // However, not throwing an exception since there is a possibility that a client
                // created an array but did not yet write data.
                // TODO: logging
            }
        }
        return new ZarrArray(relativePath, shape, chunks, dataType, byteOrder, fillValue, compressor, store, nested);
    }

    public static ZarrArray create(ArrayParams arrayParams) throws IOException {
        return create(new InMemoryStore(), arrayParams);
    }

    public static ZarrArray create(ArrayParams arrayParams, Map<String, Object> attributes) throws IOException {
        return create(new InMemoryStore(), arrayParams, attributes);
    }

    public static ZarrArray create(String path, ArrayParams params) throws IOException {
        return create(path, params, null);
    }

    public static ZarrArray create(String path, ArrayParams params, Map<String, Object> attributes) throws IOException {
        final Path fsPath = Paths.get(path);
        return create(fsPath, params, attributes);
    }

    public static ZarrArray create(Path fsPath, ArrayParams params) throws IOException {
        return create(fsPath, params, null);
    }

    public static ZarrArray create(Path fsPath, ArrayParams params, Map<String, Object> attributes) throws IOException {
        final FileSystemStore store = new FileSystemStore(fsPath);
        return create(store, params, attributes);
    }

    public static ZarrArray create(Store store, ArrayParams params) throws IOException {
        return create(store, params, null);
    }

    public static ZarrArray create(Store store, ArrayParams params, Map<String, Object> attributes) throws IOException {
        return create(new ZarrPath(""), store, params, attributes);
    }

    public static ZarrArray create(ZarrPath relativePath, Store store, ArrayParams params) throws IOException {
        return create(relativePath, store, params, null);
    }

    public static ZarrArray create(ZarrPath relativePath, Store store, ArrayParams arrayParams, Map<String, Object> attributes) throws IOException {
        store.delete(relativePath.storeKey);
        final ArrayParams.Params params = arrayParams.build();
        final int[] shape = params.getShape();
        final int[] chunks = params.getChunks();
        final DataType dataType = params.getDataType();
        final Number fillValue = params.getFillValue();
        final Compressor compressor = params.getCompressor();
        final ByteOrder byteOrder = params.getByteOrder();
        final Boolean nested = params.getNested();
        final ZarrArray zarrArray = new ZarrArray(relativePath, shape, chunks, dataType, byteOrder, fillValue, compressor, store, nested);
        zarrArray.writeZArrayHeader();
        zarrArray.writeAttributes(attributes);
        return zarrArray;
    }

    public DataType getDataType() {
        return _dataType;
    }

    public int[] getShape() {
        return Arrays.copyOf(_shape, _shape.length);
    }

    public int[] getChunks() {
        return Arrays.copyOf(_chunks, _chunks.length);
    }

    public Number getFillValue() {
        return _fillValue;
    }

    public ByteOrder getByteOrder() {
        return _byteOrder;
    }

    public boolean getNested() { return _nested; }

    public void write(Number value) throws IOException, InvalidRangeException {
        final int[] shape = getShape();
        final int[] offset = new int[shape.length];
        write(value, shape, offset);
    }

    public void write(Number value, int[] shape, int[] offset) throws IOException, InvalidRangeException {
        final Object data = ZarrUtils.createDataBufferFilledWith(value, getDataType(), shape);
        write(data, shape, offset);
    }

    public void write(Object data, int[] dataShape, int[] offset) throws IOException, InvalidRangeException {
        final int[][] chunkIndices = ZarrUtils.computeChunkIndices(_shape, _chunks, dataShape, offset);
        ucar.ma2.DataType dataType = ucar.ma2.DataType.getType(data.getClass().getComponentType(), false);
        final Array source = Array.factory(dataType, dataShape, data);
        final boolean nested = _nested == null ? DEFAULT_NESTED : _nested;

        for (int[] chunkIndex : chunkIndices) {
            final String chunkFilename = getChunkFilename(chunkIndex, nested);
            final ZarrPath chunkFilePath = relativePath.resolve(chunkFilename);
            final int[] fromBufferPos = computeFrom(chunkIndex, offset, false);
            synchronized (chunkFilename) {
                if (partialCopyingIsNotNeeded(dataShape, fromBufferPos)) {
                    _chunkReaderWriter.write(chunkFilePath.storeKey, source);
                } else {
                    final Array targetChunk = _chunkReaderWriter.read(chunkFilePath.storeKey);
                    PartialDataCopier.copy(fromBufferPos, source, targetChunk);
                    _chunkReaderWriter.write(chunkFilePath.storeKey, targetChunk);
                }
            }
        }
    }

    public Object read() throws IOException, InvalidRangeException {
        return read(getShape());
    }

    public Object read(int[] shape) throws IOException, InvalidRangeException {
        return read(shape, new int[shape.length]);
    }

    public Object read(int[] shape, int[] offset) throws IOException, InvalidRangeException {
        final Object data = ZarrUtils.createDataBuffer(getDataType(), shape);
        read(data, shape, offset);
        return data;
    }

    public void read(Object buffer, int[] bufferShape) throws IOException, InvalidRangeException {
        read(buffer, bufferShape, new int[bufferShape.length]);
    }

    public void read(Object buffer, int[] bufferShape, int[] offset) throws IOException, InvalidRangeException {
        if (!buffer.getClass().isArray()) {
            throw new IOException("Target buffer object is not an array.");
        }
        final int targetSize = java.lang.reflect.Array.getLength(buffer);
        final long expectedSize = ZarrUtils.computeSize(bufferShape);
        final boolean nested = _nested == null ? DEFAULT_NESTED : _nested;
        if (targetSize != expectedSize) {
            throw new IOException("Expected target buffer size is " + expectedSize + " but was " + targetSize);
        }
        final int[][] chunkIndices = ZarrUtils.computeChunkIndices(_shape, _chunks, bufferShape, offset);

        for (int[] chunkIndex : chunkIndices) {
            final String chunkFilename = getChunkFilename(chunkIndex, nested);
            final ZarrPath chunkFilePath = relativePath.resolve(chunkFilename);
            final int[] fromChunkPos = computeFrom(chunkIndex, offset, true);
            final Array sourceChunk = _chunkReaderWriter.read(chunkFilePath.storeKey);
            if (partialCopyingIsNotNeeded(bufferShape, fromChunkPos)) {
                System.arraycopy(sourceChunk.getStorage(), 0, buffer, 0, (int) sourceChunk.getSize());
            } else {
                final Array target = NetCDF_Util.createArrayWithGivenStorage(buffer, bufferShape);
                PartialDataCopier.copy(fromChunkPos, sourceChunk, target);
            }
        }
    }

    private synchronized String getChunkFilename(int[] chunkIndex, boolean nested) {
        String chunkFilename = ZarrUtils.createChunkFilename(chunkIndex, nested);
        if (_chunkFilenames.containsKey(chunkFilename)) {
            return _chunkFilenames.get(chunkFilename);
        }
        _chunkFilenames.put(chunkFilename, chunkFilename);
        return chunkFilename;
    }

    private boolean partialCopyingIsNotNeeded(int[] bufferShape, int[] offset) {
        return isZeroOffset(offset) && isBufferShapeEqualChunkShape(bufferShape);
    }

    private boolean isBufferShapeEqualChunkShape(int[] bufferShape) {
        return Arrays.equals(bufferShape, _chunks);
    }

    private boolean isZeroOffset(int[] offset) {
        return Arrays.equals(offset, new int[offset.length]);
    }

    public void writeAttributes(Map<String, Object> attributes) throws IOException {
        ZarrUtils.writeAttributes(attributes, relativePath, _store);
    }

    public Map<String, Object> getAttributes() throws IOException {
        return ZarrUtils.readAttributes(relativePath, _store);
    }

    @Override
    public String toString() {
        return getClass().getCanonicalName() + "{" +
               "'/" + relativePath.storeKey + "' " +
               "shape=" + Arrays.toString(_shape) +
               ", chunks=" + Arrays.toString(_chunks) +
               ", dataType=" + _dataType +
               ", fillValue=" + _fillValue +
               ", " + _compressor.toString() +
               ", store=" + _store.getClass().getSimpleName() +
               ", byteOrder=" + _byteOrder +
               '}';
    }

    private int[] computeFrom(int[] chunkIndex, int[] to, boolean read) {
        int[] from = new int[chunkIndex.length];
        for (int i = 0; i < chunkIndex.length; i++) {
            int index = chunkIndex[i];
            from[i] = index * _chunks[i];
            from[i] -= to[i];
        }
        if (read) {
            for (int i1 = 0; i1 < from.length; i1++) {
                from[i1] *= -1;
            }
        }
        return from;
    }

    private void writeZArrayHeader() throws IOException {
        final ZarrHeader zarrHeader = new ZarrHeader(_shape, _chunks, _dataType.toString(), _byteOrder, _fillValue, _compressor, _nested);
        final ZarrPath zArray = relativePath.resolve(FILENAME_DOT_ZARRAY);
        try (
                OutputStream os = _store.getOutputStream(zArray.storeKey);
                OutputStreamWriter writer = new OutputStreamWriter(os)
        ) {
            ZarrUtils.toJson(zarrHeader, writer, true);
        }
    }
}






