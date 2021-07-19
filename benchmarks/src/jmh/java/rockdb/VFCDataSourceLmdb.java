package rockdb;

import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.fcmap.VFCDataSource;
import com.swirlds.fcmap.VKey;
import com.swirlds.fcmap.VValue;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.lmdbjava.Txn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static java.nio.ByteBuffer.allocateDirect;
import static org.lmdbjava.DbiFlags.MDB_CREATE;
import static org.lmdbjava.DbiFlags.MDB_INTEGERKEY;

@SuppressWarnings("unchecked")
public final class VFCDataSourceLmdb<K extends VKey, V extends VValue> implements VFCDataSource<K, V> {
    private final static int HASH_SIZE = Long.BYTES+ DigestType.SHA_384.digestLength();
    private final Supplier<K> keyConstructor;
    private final Supplier<V> valueConstructor;
    private final Env<ByteBuffer> env;
    private final Dbi<ByteBuffer> pathToHashMap;
    private final Dbi<ByteBuffer> leafPathToKeyMap;
    private final Dbi<ByteBuffer> leafKeyToPathMap;
    private final Dbi<ByteBuffer> leafKeyToValueMap;

    private final ThreadLocal<ByteBuffer> pathBytes;
    private final ThreadLocal<ByteBuffer> hashData;
    private final ThreadLocal<ByteBuffer> leafKey;
    private final ThreadLocal<ByteBuffer> leafValue;

    /**
     * Construct a new VFCDataSourceImpl, try the static factory methods if you want a simpler way of creating one.
     *
     * @param keySizeBytes the size of key when serialized in bytes
     * @param keyConstructor constructor for creating keys for deserialization
     * @param valueSizeBytes the size of value when serialized in bytes
     * @param valueConstructor constructor for creating values for deserialization
     */
    public VFCDataSourceLmdb(int keySizeBytes, Supplier<K> keyConstructor, int valueSizeBytes, Supplier<V> valueConstructor,
                             Path storageDir) throws IOException {
        this.keyConstructor = keyConstructor;
        this.valueConstructor = valueConstructor;
        // create thread local buffers
        pathBytes = ThreadLocal.withInitial(() -> allocateDirect(Long.BYTES));
        hashData = ThreadLocal.withInitial(() -> allocateDirect(HASH_SIZE));
        leafKey = ThreadLocal.withInitial(() -> allocateDirect(Integer.BYTES + keySizeBytes));
        leafValue = ThreadLocal.withInitial(() -> allocateDirect(Integer.BYTES + valueSizeBytes));
        // create storage dirs
        if (!Files.exists(storageDir)) Files.createDirectories(storageDir);
        // We always need an Env. An Env owns a physical on-disk storage file. One
        // Env can store many different databases (ie sorted maps).
        env = Env.create()
                // LMDB also needs to know how large our DB might be. Over-estimating is OK.
                .setMapSize(1_000_000_000*(long)(keySizeBytes+keySizeBytes+valueSizeBytes+HASH_SIZE+Long.BYTES+Long.BYTES+Long.BYTES+Long.BYTES)) // TODO just a guess so far
                // LMDB also needs to know how many DBs (Dbi) we want to store in this Env.
                .setMaxDbs(4)
                // Now let's open the Env. The same path can be concurrently opened and
                // used in different processes, but do not open the same path twice in
                // the same process at the same time.
                .open(storageDir.toFile(), EnvFlags.MDB_WRITEMAP);
        // We need a Dbi for each DB. A Dbi roughly equates to a sorted map. The
        // MDB_CREATE flag causes the DB to be created if it doesn't already exist.
        pathToHashMap = env.openDbi("pathToHash", MDB_CREATE,MDB_INTEGERKEY);
        leafPathToKeyMap = env.openDbi("leafPathToKey", MDB_CREATE,MDB_INTEGERKEY);
        leafKeyToPathMap = env.openDbi("leafKeyToPath", MDB_CREATE);
        leafKeyToValueMap = env.openDbi("leafKeyToValue", MDB_CREATE);
    }

    //==================================================================================================================
    // Public API methods

    public void printStats() {
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            System.out.println("pathToHashMap.stat() = " + pathToHashMap.stat(txn));
            System.out.println("leafKeyToPathMap.stat() = " + leafKeyToPathMap.stat(txn));
            System.out.println("leafPathToKeyMap.stat() = " + leafPathToKeyMap.stat(txn));
            System.out.println("leafKeyToValueMap.stat() = " + leafKeyToValueMap.stat(txn));
        }
    }

    /**
     * Close all data stores
     */
    @Override
    public void close() {
        env.close();
    }

    /**
     * Load hash for a node with given path
     *
     * @param path the path to get hash for
     * @return loaded hash or null if hash is not stored
     * @throws IOException if there was a problem loading hash
     */
    @Override
    public Hash loadHash(long path) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer hashBytes = pathToHashMap.get(txn,getPathBytes(path));
            if (hashBytes == null) return null;
            return getHash(hashBytes);
        }
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
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer keyBytes = leafPathToKeyMap.get(txn,getPathBytes(path));
            if (keyBytes == null) return null;
            ByteBuffer valueBytes = leafKeyToValueMap.get(txn,keyBytes);
            if (valueBytes == null) return null;
            return getLeafValue(valueBytes);
        }
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
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer valueBytes = leafKeyToValueMap.get(txn,getLeafKeyBytes(key));
            if (valueBytes == null) return null;
            return getLeafValue(valueBytes);
        }
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
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer keyBytes = leafPathToKeyMap.get(txn,getPathBytes(path));
            if (keyBytes == null) return null;
            return getLeafKey(keyBytes);
        }
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
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer pathBytes = leafKeyToPathMap.get(txn,getLeafKeyBytes(key));
            if (pathBytes == null) return INVALID_PATH;
            return getPath(pathBytes);
        }
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
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            pathToHashMap.put(txn,getPathBytes(path), getHashBytes(hash));
            // commit transaction
            txn.commit();
        }
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
        if (oldPath < 0) throw new IllegalArgumentException("path is less than 0");
        if (newPath < 0) throw new IllegalArgumentException("path is less than 0");

        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            final ByteBuffer keyBytes = getLeafKeyBytes(key);
            // read hash
            final ByteBuffer oldPathKey = getPathBytes(newPath);
            // now update everything
            final ByteBuffer newPathKey = getPathBytes(newPath);
            // write hash
            pathToHashMap.put(txn, newPathKey, getHashBytes(hash));
            // write key -> path
            leafKeyToPathMap.put(txn, keyBytes, newPathKey);
            // write path -> key
            leafPathToKeyMap.put(txn, newPathKey, keyBytes);
            // commit transaction
            txn.commit();
        }
    }

    /**
     * Update a leaf at given path, the leaf must exist. Writes hash and value.
     *
     * @param path valid path to saved leaf
     * @param value the value for new leaf, can be null
     * @param hash non-null hash for the leaf
     * @throws IOException if there was a problem saving leaf update
     */
    @Override
    public void updateLeaf(long path, V value, Hash hash) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        if (hash == null) throw new IllegalArgumentException("Can not save null hash for leaf at path ["+path+"]");
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            final ByteBuffer pathBytes = getPathBytes(path);
            // read key from path
            ByteBuffer keyBytes = leafPathToKeyMap.get(txn,pathBytes);
            // write hash
            pathToHashMap.put(txn,pathBytes, getHashBytes(hash));
            // write value
            leafKeyToValueMap.put(txn, keyBytes, getLeafValueBytes(value));
            // commit transaction
            txn.commit();
        }
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
        final ByteBuffer pathBytes = getPathBytes(path);
        final ByteBuffer keyBytes = getLeafKeyBytes(key);
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            // write hash
            pathToHashMap.put(txn,pathBytes, getHashBytes(hash));
            // write key -> path
            leafKeyToPathMap.put(txn, keyBytes, pathBytes);
            // write path -> key
            leafPathToKeyMap.put(txn, pathBytes, keyBytes);
            // write value
            leafKeyToValueMap.put(txn, keyBytes, getLeafValueBytes(value));
            // commit transaction
            txn.commit();
        } catch (Exception e) {
            System.err.println("Exception addLeaf path="+path+", key="+key+", value="+value+", hash="+hash+", pathBytes="+pathBytes+", keyBytes="+keyBytes);
            throw e;
        }
    }

    //==================================================================================================================
    // Public API Transaction methods

    @Override
    public Object startTransaction() {
        return env.txnWrite();
    }

    @Override
    public void commitTransaction(Object handle) {
        Txn<ByteBuffer> txn = (Txn<ByteBuffer>)handle;
        txn.commit();
        txn.close();
    }

    /**
     * Save a hash for a internal node
     *
     * @param path the path of the node to save hash for, if nothing has been stored for this path before it will be created.
     * @param hash a non-null hash to write
     */
    @Override
    public void saveInternal(Object handle, long path, Hash hash) {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        if (hash == null)  throw new IllegalArgumentException("Hash is null");
        Txn<ByteBuffer> txn = (Txn<ByteBuffer>)handle;
        // write hash
        pathToHashMap.put(txn,getPathBytes(path), getHashBytes(hash));
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
    public void updateLeaf(Object handle, long oldPath, long newPath, K key, Hash hash) throws IOException {
        if (oldPath < 0) throw new IllegalArgumentException("path is less than 0");
        if (newPath < 0) throw new IllegalArgumentException("path is less than 0");
        Txn<ByteBuffer> txn = (Txn<ByteBuffer>)handle;
        final ByteBuffer keyBytes = getLeafKeyBytes(key);
        // now update everything
        final ByteBuffer newPathKey = getPathBytes(newPath);
        // write hash
        try {
            pathToHashMap.put(txn, newPathKey, getHashBytes(hash));
        } catch (NullPointerException npe) {
            System.out.println("handle = " + handle + ", key=" + key);
        }
        // write key -> path
        leafKeyToPathMap.put(txn, keyBytes, newPathKey);
        // write path -> key
        leafPathToKeyMap.put(txn, newPathKey, keyBytes);
    }

    /**
     * Update a leaf at given path, the leaf must exist. Writes hash and value.
     *
     * @param path valid path to saved leaf
     * @param value the value for new leaf, can be null
     * @param hash non-null hash for the leaf
     * @throws IOException if there was a problem saving leaf update
     */
    @Override
    public void updateLeaf(Object handle, long path, V value, Hash hash) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        if (hash == null) throw new IllegalArgumentException("Can not save null hash for leaf at path [" + path + "]");
        Txn<ByteBuffer> txn = (Txn<ByteBuffer>)handle;
        final ByteBuffer pathBytes = getPathBytes(path);
        // read key from path
        ByteBuffer keyBytes = leafPathToKeyMap.get(txn, pathBytes);
        // write hash
        pathToHashMap.put(txn, pathBytes, getHashBytes(hash));
        // write value
        leafKeyToValueMap.put(txn, keyBytes, getLeafValueBytes(value));
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
    public void addLeaf(Object handle, long path, K key, V value, Hash hash) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        if (hash == null) throw new IllegalArgumentException("Can not save null hash for leaf at path ["+path+"]");
        if (key == null) throw new IllegalArgumentException("Can not save null key for leaf at path ["+path+"]");
        Txn<ByteBuffer> txn = (Txn<ByteBuffer>)handle;
        final ByteBuffer pathBytes = getPathBytes(path);
        final ByteBuffer keyBytes = getLeafKeyBytes(key);
        // write hash
        pathToHashMap.put(txn,pathBytes, getHashBytes(hash));
        // write key -> path
        leafKeyToPathMap.put(txn, keyBytes, pathBytes);
        // write path -> key
        leafPathToKeyMap.put(txn, pathBytes, keyBytes);
        // write value
        leafKeyToValueMap.put(txn, keyBytes, getLeafValueBytes(value));
        // commit transaction
    }

    //==================================================================================================================
    // Private Util Methods

    private K getLeafKey(ByteBuffer leafKeyBytes) throws IOException{
        leafKeyBytes.rewind();
        final int keySerializationVersion =  leafKeyBytes.getInt();
        K key = keyConstructor.get();
        key.deserialize(leafKeyBytes,keySerializationVersion);
        return key;
    }

    private ByteBuffer getLeafKeyBytes(K key) throws IOException {
        final ByteBuffer leafKey = this.leafKey.get();
        leafKey.rewind();
        leafKey.putInt(key.getVersion());
        key.serialize(leafKey);
        return leafKey.flip();
    }

    private V getLeafValue(ByteBuffer leafValueBytes) throws IOException{
        leafValueBytes.rewind();
        final int valueSerializationVersion = leafValueBytes.getInt();
        V value = valueConstructor.get();
        value.deserialize(leafValueBytes,valueSerializationVersion);
        return value;
    }

    private ByteBuffer getLeafValueBytes(V value) throws IOException {
        final ByteBuffer leafValue = this.leafValue.get();
        leafValue.rewind();
        leafValue.putInt(value.getVersion());
        value.serialize(leafValue);
        return leafValue.flip();
    }

    private long getPath(ByteBuffer pathBytes) {
        return pathBytes.getLong(0);
    }

    private ByteBuffer getPathBytes(long path) {
        ByteBuffer pathBytes = this.pathBytes.get();
        pathBytes.rewind();
        pathBytes.putLong(path);
        return pathBytes.flip();
    }

    private Hash getHash(ByteBuffer hashBytes) throws IOException {
        hashBytes.rewind();
        return Hash.fromByteBuffer(hashBytes);
    }

    private ByteBuffer getHashBytes(Hash hash) {
        ByteBuffer hashData = this.hashData.get();
        hashData.rewind();
        Hash.toByteBuffer(hash,hashData);
        return hashData.flip();
    }
}
