package contract;

import com.hedera.services.state.merkle.v2.VFCDataSourceImpl;
import com.hedera.services.state.merkle.virtual.IdKey;
import com.hedera.services.state.merkle.virtual.ContractUint256;
import com.hedera.services.store.models.Id;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.fcmap.VFCDataSource;
import com.swirlds.fcmap.VValue;
import fcmmap.FCVirtualMapTestUtils;
import org.openjdk.jmh.annotations.*;
import rockdb.SequentialInsertsVFCDataSource;
import rockdb.VFCDataSourceLmdb;
import rockdb.VFCDataSourceLmdbTwoIndexes;
import rockdb.VFCDataSourceRocksDb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

@SuppressWarnings({"jol", "DuplicatedCode", "DefaultAnnotationParam", "SameParameterValue", "SpellCheckingInspection"})
@State(Scope.Thread)
@Warmup(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class VFCDataSourceNFTBench {
    private static final long MB = 1024*1024;

    @Param({"1000000"})
    public long numEntities;
    @Param({"5"})
    public int hashThreads;
    @Param({"1"})
    public int writeThreads;
    @Param({"2048"})
    public int nftSize;
    @Param({"memmap","lmdb","lmdb2","lmdb-ns","lmdb2-ns","rocksdb"})
    public String impl;

    // state
    public Path storePath;
    public VFCDataSource<IdKey,NFTData> dataSource;
    public Random random = new Random(1234);
    public int iteration = 0;
    private IdKey key1 = null;
    private IdKey key2 = null;
    private long randomLeafIndex1;
    private long randomLeafIndex2;
    private long randomNodeIndex1;
    private long randomNodeIndex2;
    private long nextLeafIndex;
    private NFTData aNFT;

    @Setup(Level.Trial)
    public void setup() {
        storePath = Path.of("store-"+impl);
        System.out.println("Clean Setup");
        try {
            // delete any old store
//            FCVirtualMapTestUtils.deleteDirectoryAndContents(STORE_PATH);
            final boolean storeExists = Files.exists(storePath);
            // get slot index suppliers
            dataSource = switch (impl) {
                case "memmap" ->
                    VFCDataSourceImpl.createOnDisk(storePath,numEntities*2,
                            IdKey.SERIALIZED_SIZE, IdKey::new,
                            nftSize, NFTData::new, true);
                case "lmdb","lmdb-ns" ->
                    new VFCDataSourceLmdb<>(
                            IdKey.SERIALIZED_SIZE, IdKey::new,
                            nftSize, NFTData::new,
                            storePath);
                case "lmdb2","lmdb2-ns" ->
                    new VFCDataSourceLmdbTwoIndexes<>(
                            IdKey.SERIALIZED_SIZE, IdKey::new,
                            nftSize, NFTData::new,
                            storePath);
                case "rocksdb" ->
                    new VFCDataSourceRocksDb<>(
                            IdKey.SERIALIZED_SIZE, IdKey::new,
                            nftSize, NFTData::new,
                            storePath);
                default ->
                    throw new IllegalStateException("Unexpected value: " + impl);
            };
            // create data
            aNFT = new NFTData(nftSize);
            if (!storeExists) {
                final SequentialInsertsVFCDataSource<IdKey,NFTData> sequentialDataSource =
                    (dataSource instanceof SequentialInsertsVFCDataSource && (impl.equals("lmdb") || impl.equals("lmdb2")))
                            ? (SequentialInsertsVFCDataSource<IdKey,NFTData>)dataSource : null;
                System.out.println("================================================================================");
                System.out.println("Creating data ...");
                long printStep = Math.min(500_000, numEntities / 4);
                final AtomicLong I = new AtomicLong(0);
                final AtomicLong START = new AtomicLong( System.currentTimeMillis());

                IntStream.range(0,writeThreads).parallel().forEach(c -> {
                    long i;
                    long iHaveWritten = 0;
                    Object transaction = dataSource.startTransaction();
                    while ((i = I.getAndIncrement()) < numEntities) {
                        if (i != 0 && i % printStep == 0) {
                            long start = START.getAndSet(System.currentTimeMillis());
                            printUpdate(start, printStep, ContractUint256.SERIALIZED_SIZE, "Created " + i + " Nodes");
                        }
                        if (iHaveWritten != 0 && iHaveWritten % printStep == 0) { // next transaction
                            dataSource.commitTransaction(transaction);
                            transaction = dataSource.startTransaction();
                        }
                        try {
                            if (sequentialDataSource != null) {
                                sequentialDataSource.saveInternalSequential(transaction, i, FCVirtualMapTestUtils.hash((int) i));
                            } else {
                                dataSource.saveInternal(transaction, i, FCVirtualMapTestUtils.hash((int) i));
                            }
                        } catch (Exception e) {
                            System.err.println("Error i= "+i);
                            e.printStackTrace();
                        }
                        iHaveWritten ++;
                    }
                    dataSource.commitTransaction(transaction);
                });

                // start leaves
                I.set(0);
                START.set(System.currentTimeMillis());
                IntStream.range(0,writeThreads).parallel().forEach(c -> {
                    long i;
                    long iHaveWritten = 0;
                    Object transaction = dataSource.startTransaction();
                    while ((i = I.getAndIncrement()) < numEntities) {
                        if (i != 0 && i % printStep == 0) {
                            long start = START.getAndSet(System.currentTimeMillis());
                            printUpdate(start, printStep, ContractUint256.SERIALIZED_SIZE, "Created " + i + " Nodes");
                        }
                        if (iHaveWritten != 0 && iHaveWritten % printStep == 0) {
                            dataSource.commitTransaction(transaction);
                            transaction = dataSource.startTransaction();
                        }
                        try {
                            if (sequentialDataSource != null) {
                                ((SequentialInsertsVFCDataSource<IdKey,NFTData>) dataSource).addLeafSequential(transaction, numEntities + i, new IdKey(new Id(0, 0, i)), aNFT, FCVirtualMapTestUtils.hash((int) i));
                            } else {
                                dataSource.addLeaf(transaction, numEntities + i, new IdKey(new Id(0, 0, i)), aNFT, FCVirtualMapTestUtils.hash((int) i));
                            }
                        } catch (Exception e) {
                            System.err.println("Error i= "+i);
                            e.printStackTrace();
                        }
                        iHaveWritten ++;
                    }
                    dataSource.commitTransaction(transaction);
                });

                System.out.println("================================================================================");
                nextLeafIndex = numEntities;
                // reset iteration counter
                iteration = 0;
            } else {
                System.out.println("Loaded existing data");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printUpdate(long START, long count, int size, String msg) {
        long took = System.currentTimeMillis() - START;
        double timeSeconds = (double)took/1000d;
        double perSecond = (double)count / timeSeconds;
        double mbPerSec = (perSecond*size)/MB;
        System.out.printf("%s : [%,d] writes at %,.0f per/sec %,.1f Mb/sec, took %,.2f seconds\n",msg, count, perSecond,mbPerSec, timeSeconds);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        try {
            dataSource.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        FCVirtualMapTestUtils.printDirectorySize(storePath);
    }

    @Setup(Level.Invocation)
    public void randomIndex(){
        randomNodeIndex1 = (long)(random.nextDouble()*numEntities);
        randomNodeIndex2 = (long)(random.nextDouble()*numEntities);
        randomLeafIndex1 = numEntities + randomNodeIndex1;
        randomLeafIndex2 = numEntities + randomNodeIndex2;
        key1 = new IdKey(new Id(0,0,randomNodeIndex1));
        key2 = new IdKey(new Id(0,0,randomNodeIndex2));
    }


    @Benchmark
    public void w0_updateHash() throws Exception {
        dataSource.saveInternal(randomLeafIndex1, FCVirtualMapTestUtils.hash((int) randomLeafIndex1));
    }

    @Benchmark
    public void w1_updateLeafValue() throws Exception {
        dataSource.updateLeaf(randomLeafIndex1,new IdKey(new Id(0,0,randomLeafIndex1)),
                aNFT, FCVirtualMapTestUtils.hash((int) randomNodeIndex2));
    }

    @Benchmark
    public void w2_addLeaf() throws Exception {
        dataSource.addLeaf(numEntities + nextLeafIndex, new IdKey(new Id(0,0,nextLeafIndex)), aNFT, FCVirtualMapTestUtils.hash((int) nextLeafIndex));
        nextLeafIndex++;
    }

    @Benchmark
    public void w3_MoveLeaf() throws Exception {
        dataSource.updateLeaf(randomLeafIndex1,randomLeafIndex2,key1, FCVirtualMapTestUtils.hash((int) randomLeafIndex1));
    }

    /**
     * This is designed to mimic our transaction round
     */
    @Benchmark
    public void t_transaction() {
        IntStream.range(0,3).parallel().forEach(thread -> {
            try {
                switch (thread) {
                    case 0 -> {
                        Thread.currentThread().setName("transaction");
                        // this is the transaction thread that reads leaf values
                        for (int i = 0; i < 20_000; i++) {
                            randomNodeIndex1 = (long) (random.nextDouble() * numEntities);
                            key2 = new IdKey(new Id(0, 0, randomNodeIndex1));
                            dataSource.loadLeafValue(key2);
                        }
                    }
                    case 1 -> {
                        // this is the hashing thread that reads hashes
                        final int chunk = 20_000/hashThreads;
                        IntStream.range(0,hashThreads).parallel().forEach(hashChunk -> {
                            Thread.currentThread().setName("hashing "+hashChunk);
                            for (int i = 0; i < chunk; i++) {
                                randomNodeIndex1 = (long) (random.nextDouble() * numEntities);
                                try {
                                    dataSource.loadHash(randomNodeIndex1);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                    case 2 -> {
                        Thread.currentThread().setName("archiver");
                        // this is the archive thread that writes nodes and leaves
                        final int chunk = 20_000/writeThreads;
                        IntStream.range(0,writeThreads/2).parallel().forEach(c -> {
                            Thread.currentThread().setName("writing internals "+c);
                            final Object transaction = dataSource.startTransaction();
                            for (int i = 0; i < chunk; i++) { // update 10k internal hashes
                                randomNodeIndex1 = (long) (random.nextDouble() * numEntities);
                                try {
                                    dataSource.saveInternal(transaction,randomLeafIndex1, FCVirtualMapTestUtils.hash((int) randomLeafIndex1));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                            dataSource.commitTransaction(transaction);
                        });
                        IntStream.range(0,writeThreads/2).parallel().forEach(c -> {
                            Thread.currentThread().setName("writing leaves "+c);
                            final Object transaction = dataSource.startTransaction();
                            for (int i = 0; i < chunk; i++) { // update 10k leaves
                                randomNodeIndex1 = (long) (random.nextDouble() * numEntities);
                                randomLeafIndex1 = numEntities + randomNodeIndex1;
                                key1 = new IdKey(new Id(0, 0, randomNodeIndex1));
                                try {
                                    dataSource.updateLeaf(transaction,randomLeafIndex1,key1, aNFT, FCVirtualMapTestUtils.hash((int) randomNodeIndex1));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                            dataSource.commitTransaction(transaction);
                        });
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    @Benchmark
    public void r_loadLeafPath() throws Exception {
        dataSource.loadLeafPath(key1);
    }

    @Benchmark
    public void r_loadLeafKey() throws Exception {
        dataSource.loadLeafKey(randomLeafIndex1);
    }

    @Benchmark
    public void r_loadLeafValueByPath() throws Exception {
        dataSource.loadLeafValue(randomLeafIndex2);
    }

    @Benchmark
    public void r_loadLeafValueByKey() throws Exception {
        dataSource.loadLeafValue(key2);
    }

    @Benchmark
    public void r_loadHash() throws Exception {
        dataSource.loadHash(randomNodeIndex1);
    }

    public static class NFTData implements VValue {
        private static Random RANDOM = new Random();
        
        ByteBuffer randomData;

        public NFTData() {}
        
        public NFTData(int size) {
            randomData = ByteBuffer.allocate(size-Integer.BYTES);
            RANDOM.nextBytes(randomData.array());
        }

        public NFTData(NFTData toCopy) {
            this.randomData = toCopy.randomData;
        }

        @Override
        public void serialize(ByteBuffer byteBuffer) throws IOException {
            randomData.rewind();
            byteBuffer.putInt(randomData.limit());
            byteBuffer.put(randomData);
        }

        @Override
        public void deserialize(ByteBuffer byteBuffer, int i) throws IOException {
            int size = byteBuffer.getInt();
            randomData = ByteBuffer.allocate(size);
            randomData.put(byteBuffer);
        }

        @Override
        public void update(ByteBuffer byteBuffer) throws IOException {
            randomData.rewind();
            byteBuffer.putInt(randomData.limit());
            byteBuffer.put(randomData);
        }

        @Override
        public NFTData copy() {
            return new NFTData(this);
        }

        @Override
        public NFTData asReadOnly() {
            return this;
        }

        @Override
        public void release() {
            randomData = null;
        }

        @Override
        public void deserialize(SerializableDataInputStream serializableDataInputStream, int i) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getClassId() {
            return 35468187315L;
        }

        @Override
        public void serialize(SerializableDataOutputStream serializableDataOutputStream) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getVersion() {
            return 1;
        }
    }
}
