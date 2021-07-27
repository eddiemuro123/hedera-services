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
import java.util.concurrent.TimeUnit;
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
public class Pipeline<K extends VirtualKey, V extends VirtualValue> {
    /** Used to pass a virtual map from the handleTransaction (main) thread to the hashing thread */
    private final Exchanger<HashingData> hashingExchanger = new Exchanger<>();
    /** Used to pass a virtual map from the holder thread to the release thread, where it will be released / merged */
    private final Exchanger<VirtualMap<K, V>> releaseExchanger = new Exchanger<>();

    /** This thread will hash the virtual map (which has a bunch of additional background threads) */
    private final ExecutorService hashingService = Executors.newSingleThreadExecutor(threadFactory("HashingService"));
    /** This thread will release the map and pass the map to the archive service */
    private final ExecutorService releaseService = Executors.newSingleThreadExecutor(threadFactory("ReleaseService"));
    /** This thread will archive the most recent map once per minute (the others it throws away) */
    private final ScheduledExecutorService archiveService = Executors.newSingleThreadScheduledExecutor(threadFactory("ArchiveService"));

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
        releaseService.submit(new Task(() -> {
            final var map = releaseExchanger.exchange(null);
//            while (masterMap.get() != null) {
//                Thread.sleep(50);
//            }
            synchronized (masterMap) {
                final var master = masterMap.get();
                if (master != null) {
                    master.merge(); // Merges "master" into "map".
                }
                masterMap.set(map);
            }
        }));

        // Runs once a minute and grabs the latest "master" map. If not null, it archives it.
        archiveService.scheduleWithFixedDelay(() -> {
            try {
                synchronized (masterMap) {
//                    final var map = masterMap.getAndSet(null);
//                    if (map != null) {
//                        map.archive();
//                        System.out.println("Archived");
//                    }
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);
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

    private ThreadFactory threadFactory(String namePrefix) {
        return r -> {
            Thread th = new Thread(r);
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
