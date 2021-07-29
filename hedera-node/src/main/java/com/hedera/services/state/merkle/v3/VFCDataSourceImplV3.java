package com.hedera.services.state.merkle.v3;

import com.hedera.services.state.merkle.v3.files.DataFile;
import com.hedera.services.state.merkle.v3.files.HalfDiskHashMap;
import com.hedera.services.state.merkle.v3.files.MemoryIndexDiskKeyValueStore;
import com.hedera.services.state.merkle.v3.offheap.OffHeapHashList;
import com.hedera.services.state.merkle.v3.offheap.OffHeapLongList;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualLongKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualInternalRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static java.nio.ByteBuffer.allocateDirect;

/**
 * IMPORTANT: This implementation assumes a single writing thread. There can be multiple readers while writing is happening.
 *
 * @param <K>
 * @param <V>
 */
public class VFCDataSourceImplV3<K extends VirtualKey, V extends VirtualValue> implements VirtualDataSource<K, V> {
    private final static int HASH_SIZE = Integer.BYTES+ DigestType.SHA_384.digestLength(); // TODO remove please for something better
    private final int keySizeBytes;
    private final int valueSizeBytes;
    private final Supplier<K> keyConstructor;
    private final Supplier<V> valueConstructor;
    private final boolean isLongKeyMode;
    private final OffHeapHashList hashStore = new OffHeapHashList();
    private final OffHeapLongList longKeyToPath;
    private final HalfDiskHashMap<K> objectKeyToPath;
    private final MemoryIndexDiskKeyValueStore pathToKeyHashValue;
    private final ThreadLocal<ByteBuffer> leafKey;
    private final ThreadLocal<ByteBuffer> keyHashValue;

    /**
     * Create new VFCDataSourceImplV3
     *
     * @param keySizeBytes the size of key when serialized in bytes
     * @param keyConstructor constructor for creating keys for deserialization
     * @param valueSizeBytes the size of value when serialized in bytes
     * @param valueConstructor constructor for creating values for deserialization
     * @param storageDir directory to store data files in
     * @param maxNumOfKeys the maximum number of unique keys. This is used for calculating in memory index sizes
     */
    public VFCDataSourceImplV3(int keySizeBytes, Supplier<K> keyConstructor, int valueSizeBytes, Supplier<V> valueConstructor,
                                Path storageDir, long maxNumOfKeys) throws IOException {
        this.keySizeBytes = Integer.BYTES + keySizeBytes;
        this.keyConstructor = keyConstructor;
        this.valueSizeBytes = Integer.BYTES + valueSizeBytes;
        this.valueConstructor = valueConstructor;
        this.leafKey = ThreadLocal.withInitial(() -> allocateDirect(this.keySizeBytes));
        this.keyHashValue = ThreadLocal.withInitial(() -> allocateDirect(this.keySizeBytes+HASH_SIZE+this.valueSizeBytes));
        if (keySizeBytes == Long.BYTES) {
            isLongKeyMode = true;
            longKeyToPath = new OffHeapLongList();
            objectKeyToPath = null;
        } else {
            isLongKeyMode = false;
            longKeyToPath =  null;
            objectKeyToPath = new HalfDiskHashMap<>(maxNumOfKeys,keySizeBytes,keyConstructor,storageDir,"objectKeyToPath");
        }
        final int keyHashValueSize = this.keySizeBytes + HASH_SIZE + this.valueSizeBytes;
        System.out.println("keyHashValueSize = " + keyHashValueSize);
        final int keyHashValueSizePower2 = Integer.highestOneBit(keyHashValueSize)*2; // nearest greater power of two
        System.out.println("keyHashValueSizePower2 = " + keyHashValueSizePower2);
        pathToKeyHashValue = new MemoryIndexDiskKeyValueStore(storageDir,"pathToKeyHashValue",keyHashValueSizePower2);
    }


    //==================================================================================================================
    // Public NEW API methods

    public VirtualLeafRecord<K, V> loadLeafRecord(K key) throws IOException {
        if (key == null) throw new IllegalArgumentException("key can not be null");
        long path = isLongKeyMode ? longKeyToPath.get(((VirtualLongKey)key).getKeyAsLong(), INVALID_PATH) : objectKeyToPath.get(key, INVALID_PATH);
        if (path == INVALID_PATH) return null;
        return loadLeafRecord(path,key);
    }

    public VirtualLeafRecord<K, V> loadLeafRecord(long path) throws IOException {
        return loadLeafRecord(path,null);
    }

    /**
     * load a leaf record by path, using the provided key or if null deserializing the key.
     */
    private VirtualLeafRecord<K, V> loadLeafRecord(long path, K key) throws IOException {
        // get reusable buffer
        ByteBuffer keyHashValueBuffer = this.keyHashValue.get().clear();
        // read value
        boolean found = pathToKeyHashValue.get(path,keyHashValueBuffer, DataFile.DataToRead.VALUE);
        if (!found) return null;
        // deserialize
        keyHashValueBuffer.rewind();
        // deserialize key
        if (key != null) {
            keyHashValueBuffer.position(keySizeBytes); // jump key
        } else {
            final int keySerializationVersion = keyHashValueBuffer.getInt();
            key = keyConstructor.get();
            key.deserialize(keyHashValueBuffer, keySerializationVersion);
        }
        // deserialize hash
        final Hash hash = Hash.fromByteBuffer(keyHashValueBuffer);
        // deserialize value
        final int valueSerializationVersion = keyHashValueBuffer.getInt();
        final V value = valueConstructor.get();
        value.deserialize(keyHashValueBuffer,valueSerializationVersion);
        // return new VirtualLeafRecord
        return new VirtualLeafRecord<>(path, hash, key, value);
    }

    /**
     * Save a batch of data to data store
     */
    public void saveRecords(long firstLeafPath, long lastLeafPath, List<VirtualInternalRecord> internalRecords, List<VirtualLeafRecord<K, V>> leafRecords) {
        // TODO work out how to delete things using firstLeafPath & lastLeafPath
        // might as well write to the 3 data stores in parallel
        IntStream.range(0,3).parallel().forEach(action -> {
            if (action == 0) {// write internal node hashes
                if (internalRecords != null && !internalRecords.isEmpty()) {
                    for (VirtualInternalRecord rec : internalRecords) {
                        hashStore.put(rec.getPath(), rec.getHash());
                    }
                }
            } else if (action == 1) { // write leaves to pathToKeyHashValue
                if (leafRecords != null && !leafRecords.isEmpty()) {
                    try {
                        pathToKeyHashValue.startWriting();
                        // get reusable buffer
                        ByteBuffer keyHashValueBuffer = this.keyHashValue.get();
                        for (var rec : leafRecords) {
                            final long path = rec.getPath();
                            final VirtualKey key = rec.getKey();
                            final Hash hash = rec.getHash();
                            final VirtualValue value = rec.getValue();
                            // clear buffer for reuse
                            keyHashValueBuffer.clear();
                            // put key
                            keyHashValueBuffer.putInt(key.getVersion());
                            key.serialize(keyHashValueBuffer);
                            // put hash
                            Hash.toByteBuffer(hash, keyHashValueBuffer);
                            // put value
                            keyHashValueBuffer.putInt(value.getVersion());
                            value.serialize(keyHashValueBuffer);
                            // now save pathToKeyHashValue
                            keyHashValueBuffer.flip();
                            pathToKeyHashValue.put(path, keyHashValueBuffer);
                        }
                        pathToKeyHashValue.endWriting();
                    } catch (IOException e) {
                        throw new RuntimeException(e); // TODO maybe re-wrap into IOException?
                    }
                }
            } else if (action == 2) { // write leaves to objectKeyToPath
                if (leafRecords != null && !leafRecords.isEmpty()) {
                    if (isLongKeyMode) {
                        for (var rec : leafRecords) {
                            longKeyToPath.put(((VirtualLongKey) rec.getKey()).getKeyAsLong(), rec.getPath());
                        }
                    } else {
                        try {
                            objectKeyToPath.startWriting();
                            for (var rec : leafRecords) {
                                objectKeyToPath.put(rec.getKey(), rec.getPath());
                            }
                            objectKeyToPath.endWriting();
                        } catch (IOException e) {
                            throw new RuntimeException(e); // TODO maybe re-wrap into IOException?
                        }
                    }
                }
            }
        });
    }

    /**
     * Load hash for a leaf node with given path
     *
     * @param path the path to get hash for
     * @return loaded hash or null if hash is not stored
     * @throws IOException if there was a problem loading hash
     */
    @Override
    public Hash loadLeafHash(long path) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        ByteBuffer keyHashValueBuffer = this.keyHashValue.get().clear();
        // read value
        boolean found = pathToKeyHashValue.get(path,keyHashValueBuffer, DataFile.DataToRead.VALUE);
        if (!found) return null;
        // deserialize hash
        keyHashValueBuffer.rewind();
        keyHashValueBuffer.position(keySizeBytes); // jump key
        return Hash.fromByteBuffer(keyHashValueBuffer);
    }

    /**
     * Load hash for a internal node with given path
     *
     * @param path the path to get hash for
     * @return loaded hash or null if hash is not stored
     * @throws IOException if there was a problem loading hash
     */
    @Override
    public Hash loadInternalHash(long path) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        return hashStore.get(path);
    }

    //==================================================================================================================
    // Legacy API methods

    /**
     * Close all data stores
     */
    @Override
    public void close() throws IOException {
        pathToKeyHashValue.close();
    }

    /**
     * Load leaf's value
     *
     * @param path the path for leaf to get value for
     * @return loaded leaf value or null if none was saved
     * @throws IOException if there was a problem loading leaf data
     */
    @Override
    public V loadLeafValue(long path) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        ByteBuffer keyHashValueBuffer = this.keyHashValue.get().clear();
        // read value
        boolean found = pathToKeyHashValue.get(path,keyHashValueBuffer, DataFile.DataToRead.VALUE);
        if (!found) return null;
        // deserialize value
        keyHashValueBuffer.rewind();
        keyHashValueBuffer.position(keySizeBytes+HASH_SIZE); // jump over key and hash, TODO add API to read at starting offset, to avoid this
        final int valueSerializationVersion = keyHashValueBuffer.getInt();
        V value = valueConstructor.get();
        value.deserialize(keyHashValueBuffer,valueSerializationVersion);
        return value;
    }

    /**
     * Load leaf's value
     *
     * @param key the key for leaf to get value for
     * @return loaded leaf value or null if none was saved
     * @throws IOException if there was a problem loading leaf data
     */
    @Override
    public V loadLeafValue(K key) throws IOException {
        if (key == null) throw new IllegalArgumentException("key can not be null");
        long path = isLongKeyMode ? longKeyToPath.get(((VirtualLongKey)key).getKeyAsLong(), INVALID_PATH) : objectKeyToPath.get(key, INVALID_PATH);
        if (path == INVALID_PATH) return null;
        return loadLeafValue(path);
    }

    /**
     * Load a leaf's key
     *
     * @param path the path to the leaf to load key for
     * @return the loaded key for leaf or null if none was saved
     * @throws IOException if there was a problem loading key
     */
    @Override
    public K loadLeafKey(long path) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        ByteBuffer keyBuffer = this.leafKey.get().clear();
        // read key
        boolean found = pathToKeyHashValue.get(path,keyBuffer, DataFile.DataToRead.VALUE);
        if (!found) return null;
        keyBuffer.rewind();
        final int keySerializationVersion =  keyBuffer.getInt();
        K key = keyConstructor.get();
        key.deserialize(keyBuffer,keySerializationVersion);
        return key;
    }

    /**
     * Load path for a leaf
     *
     * @param key the key for the leaf to get path for
     * @return loaded path or null if none is stored for key
     * @throws IOException if there was a problem loading leaf's path
     */
    @Override
    public long loadLeafPath(K key) throws IOException {
        return isLongKeyMode ? longKeyToPath.get(((VirtualLongKey)key).getKeyAsLong(), INVALID_PATH) : objectKeyToPath.get(key, INVALID_PATH);
    }

    /**
     * Save a hash for a internal node
     *
     * @param path the path of the node to save hash for, if nothing has been stored for this path before it will be created.
     * @param hash a non-null hash to write
     */
    @Override
    public void saveInternal(long path, Hash hash) {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        if (hash == null)  throw new IllegalArgumentException("Hash is null");
        // write hash
        hashStore.put(path,hash);
    }

    /**
     * Update a leaf moving it from one path to another. Note! any existing node at the newPath will be overridden.
     *
     * @param oldPath Must be an existing valid path
     * @param newPath Can be larger than current max path, allowing tree to grow
     * @param key The key for the leaf so we can update key->path index
     * @throws IOException if there was a problem saving leaf update
     */
    @Override
    public void updateLeaf(long oldPath, long newPath, K key, Hash hash) throws IOException {
        addLeaf(newPath,key,loadLeafValue(oldPath),hash);
    }

    /**
     * Update a leaf at given path, the leaf must exist. Writes hash and value.
     *
     * @param path valid path to saved leaf
     * @param key the leaf's key
     * @param value the value for new leaf, can be null
     * @param hash non-null hash for the leaf
     * @throws IOException if there was a problem saving leaf update
     */
    @Override
    public void updateLeaf(long path, K key, V value, Hash hash) throws IOException {
        addLeaf(path, key,value, hash);
    }

    /**
     * Add a new leaf to store
     *
     * @param path the path for the new leaf
     * @param key the non-null key for the new leaf
     * @param value the value for new leaf, can be null
     * @param hash the non-null hash for new leaf
     * @throws IOException if there was a problem writing leaf
     */
    @Override
    public void addLeaf(long path, K key, V value, Hash hash) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        if (hash == null) throw new IllegalArgumentException("Can not save null hash for leaf at path ["+path+"]");
        if (key == null) throw new IllegalArgumentException("Can not save null key for leaf at path ["+path+"]");
        // fill buffer
        ByteBuffer keyHashValueBuffer = this.keyHashValue.get().clear();
        // put key
        keyHashValueBuffer.putInt(key.getVersion());
        key.serialize(keyHashValueBuffer);
        // put hash
        Hash.toByteBuffer(hash,keyHashValueBuffer);
        // put value
        keyHashValueBuffer.putInt(value.getVersion());
        value.serialize(keyHashValueBuffer);
        // now save pathToKeyHashValue
        keyHashValueBuffer.flip();
        pathToKeyHashValue.put(path,keyHashValueBuffer);
        // save key to path mapping
        if (isLongKeyMode) {
            longKeyToPath.put(((VirtualLongKey)key).getKeyAsLong(),path);
        } else {
            objectKeyToPath.put(key,path);
        }
    }

    //==================================================================================================================
    // Legacy API Transaction methods

    @Override
    public Object startTransaction() {
        try {
            pathToKeyHashValue.startWriting();
            if (!isLongKeyMode) objectKeyToPath.startWriting();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void commitTransaction(Object handle) {
        try {
            pathToKeyHashValue.endWriting();
            if (!isLongKeyMode) objectKeyToPath.endWriting();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
