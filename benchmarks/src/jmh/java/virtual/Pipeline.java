package virtual;

import com.swirlds.common.crypto.Hash;
import com.swirlds.common.threading.InterruptableRunnable;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualValue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Pipeline progresses a virtual map from the handleTransaction phase to the
 * hashing phase to the release/merge phase and ultimately to the archive phase.
 * There may be more phases too. In a way, it simulates what EventFlow does in
 * the SDK.
 *
 * Main/handleTransaction -> HashingService -> HolderService -> ReleaseService -> ArchiveService
 *
 * @param <K>
 * @param <V>
 */
@SuppressWarnings("FieldCanBeLocal")
public class Pipeline<K extends VirtualKey, V extends VirtualValue> {
    /** Used to pass a virtual map from the handleTransaction (main) thread to the hashing thread */
    private final Exchanger<HashingData> hashingExchanger = new Exchanger<>();
    /** Used to pass a virtual map from the holder thread to the release thread, where it will be released / merged */
    private final Exchanger<VirtualMap<K, V>> releaseExchanger = new Exchanger<>();
    /** Used to pass a virtual map from the holder thread to the archive thread, where it will be archived to disk */
    private final Exchanger<VirtualMap<K, V>> archiveExchanger = new Exchanger<>();

    /** This thread will hash the virtual map (which has a bunch of additional background threads) */
    private final ExecutorService hashingService = Executors.newSingleThreadExecutor(threadFactory("HashingService", null));
    /** This thread will release the map and pass the map to the archive service */
    private final ExecutorService releaseService = Executors.newSingleThreadExecutor(threadFactory("ReleaseService", null));
    /** This thread will archive the most recent map every N rounds */
    private final ExecutorService archiveService = Executors.newSingleThreadExecutor(threadFactory("ArchiveService", null));

    /**
     * This future is created by the hashing thread and passed back to the handle transaction thread via
     * the hashingExchanger. It allows us to block until hashing is completed before creating the next
     * fast copy, so that we only fast-copy the virtual map when hashing has been completed.
     */
    private Future<Hash> hashingFuture = null;

    /**
     * Create a new pipeline. Spawns all the threads.
     */
    public Pipeline() {
        hashingService.submit(new Task(() -> {
            final var cf = new CompletableFuture<Hash>();
            final var map = hashingExchanger.exchange(new HashingData(cf)).map;
            try {
                final var hashingFuture = map.hash();
                cf.complete(hashingFuture.get()); // blocks, which is what I want.
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                releaseExchanger.exchange(map);
            }
        }));

        // If null, the next map we read goes into here. If not null, the next
        // map we get will be merged into it. At some point, the archive thread
        // will wake up and get the map setting it back to null, at which point
        // we start the process over again.
        final var masterMap = new AtomicReference<VirtualMap<K, V>>(null);
        final var finishedArchiving = new AtomicBoolean(true);
        final var mergedRoundsCount = new AtomicInteger(0);
        releaseService.submit(new Task(() -> {
            final var map = releaseExchanger.exchange(null);
            synchronized (masterMap) {
                final var master = masterMap.get();
                if (master != null) {
                    master.merge(); // Merges "master" into "map".
                }
                masterMap.set(map);
            }
            var currentMergedRoundsCount = mergedRoundsCount.incrementAndGet();
            if (currentMergedRoundsCount >= 30 && finishedArchiving.get()) {
                mergedRoundsCount.set(0);
                // start a new archiving job, if we have completed at least 30 rounds and the last archiving job is complete
                finishedArchiving.set(false);
                archiveExchanger.exchange(masterMap.getAndSet(null));
            }
        }));

        // Runs once a minute and grabs the latest "master" map. If not null, it archives it.
        archiveService.execute(new Task(() -> {
            final var map = archiveExchanger.exchange(null);
            if (map != null) {
                map.archive();
                finishedArchiving.set(true);
            }
        }));
    }

    public VirtualMap<K, V> endRound(VirtualMap<K, V> virtualMap) {
        try {
            // Block on a previous hash job, if there is one
            if (hashingFuture != null) {
                hashingFuture.get();
            }

            // Make our fast copy
            final var newMap = virtualMap.copy();

            // Exchange our fast copy for a new hashing future
            hashingFuture = hashingExchanger.exchange(new HashingData(virtualMap)).hashingFuture;
            return newMap;
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    public static ThreadFactory threadFactory(String namePrefix, ThreadGroup group) {
        return r -> {
            Thread th = group == null ? new Thread(r) : new Thread(group, r);
            th.setName(namePrefix);
            th.setDaemon(true);
            th.setUncaughtExceptionHandler((t, e) -> {
                e.printStackTrace();
            });
            return th;
        };
    }

    private final class HashingData {
        Future<Hash> hashingFuture;
        VirtualMap<K, V> map;

        // Created when passing a HashingData from handleTransaction to the hashing service
        public HashingData(VirtualMap<K, V> map) {
            this.hashingFuture = null;
            this.map = map;
        }

        // Created when passing a HashingData from the hashing service back to handleTransaction
        public HashingData(Future<Hash> hashingFuture) {
            this.map = null;
            this.hashingFuture = hashingFuture;
        }
    }

    private static final class Task implements Runnable {
        private final InterruptableRunnable r;

        public Task(InterruptableRunnable r) {
            this.r = r;
        }

        @Override
        public void run() {
            //noinspection InfiniteLoopStatement
            while (true) {
                try {
                    this.r.run();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
