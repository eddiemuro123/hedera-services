package com.hedera.services.stream;

import com.hedera.services.stats.MiscRunningAvgs;
import com.swirlds.common.Platform;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.ImmutableHash;
import com.swirlds.common.crypto.SerializableRunningHashable;
import com.swirlds.common.stream.HashCalculatorForStream;
import com.swirlds.common.stream.MultiStream;
import com.swirlds.common.stream.QueueThread;
import com.swirlds.common.stream.RunningHashCalculatorForStream;
import com.swirlds.common.stream.TimestampStreamFileWriter;
import com.swirlds.common.stream.Timestamped;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static com.swirlds.common.Constants.SEC_TO_MS;

/**
 * This class is used for generating record stream files when record streaming is enabled,
 * and for calculating runningHash for {@link RecordStreamObject}s
 */
public class RecordStreamManager<T extends Timestamped & SerializableRunningHashable> {
	/** use this for all logging, as controlled by the optional data/log4j2.xml file */
	private static final Logger LOGGER = LogManager.getLogger();

	/**
	 * receives {@link RecordStreamObject}s from {@link com.hedera.services.legacy.services.state.AwareProcessLogic}
	 * .addForStreaming,
	 * then passes to hashQueueThread and writeQueueThread
	 */
	private MultiStream<T> multiStream;

	/** receives {@link RecordStreamObject}s from multiStream, then passes to hashCalculator */
	private QueueThread<T> hashQueueThread;
	/**
	 * receives {@link RecordStreamObject}s from hashQueueThread, calculates this object's Hash, then passes to
	 * runningHashCalculator
	 */
	private HashCalculatorForStream<T> hashCalculator;
	/** receives {@link RecordStreamObject}s from hashCalculator, calculates and set runningHash for this object */
	private RunningHashCalculatorForStream<T> runningHashCalculator;

	/** receives {@link RecordStreamObject}s from multiStream, then passes to streamFileWriter */
	private QueueThread<T> writeQueueThread;
	/**
	 * receives {@link RecordStreamObject}s from writeQueueThread, serializes {@link RecordStreamObject}s to record
	 * stream files
	 */
	private TimestampStreamFileWriter<T> streamFileWriter;

	/** initial running Hash of records */
	private Hash initialHash = new ImmutableHash(new byte[DigestType.SHA_384.digestLength()]);

	/**
	 * when record streaming is started after reconnect, or at state recovering, startWriteAtCompleteWindow should be
	 * set
	 * to be true;
	 * when record streaming is started after restart, it should be set to be false
	 */
	private boolean startWriteAtCompleteWindow = false;

	/**
	 * whether the platform is in freeze period
	 */
	private volatile boolean inFreeze = false;

	/**
	 * an instance for recording the average value of recordStream queue size
	 */
	private MiscRunningAvgs runningAvgs;

	/**
	 * @param platform
	 * 		the platform which initializes this RecordStreamManager instance
	 * @param runningAvgs
	 * 		an instance for recording the average value of recordStream queue size
	 * @param enableRecordStreaming
	 * 		whether write record stream files or not
	 * @param recordStreamDir
	 * 		the directory to which record stream files are written
	 * @param recordsLogPeriod
	 * 		period of generating recordStream file
	 * @param recordStreamQueueCapacity
	 * 		capacity of the blockingQueue from which we take records and write to RecordStream files
	 * @throws NoSuchAlgorithmException
	 * 		is thrown when fails to get required MessageDigest instance
	 * @throws IOException
	 * 		is thrown when fails to create directory for record streaming
	 */
	public RecordStreamManager(final Platform platform,
			final MiscRunningAvgs runningAvgs,
			final boolean enableRecordStreaming,
			final String recordStreamDir,
			final long recordsLogPeriod,
			final int recordStreamQueueCapacity) throws NoSuchAlgorithmException, IOException {
		if (enableRecordStreaming) {
			// the directory to which record stream files are written
			Files.createDirectories(Paths.get(recordStreamDir));

			streamFileWriter = new TimestampStreamFileWriter<>(
					recordStreamDir,
					recordsLogPeriod * SEC_TO_MS,
					platform,
					startWriteAtCompleteWindow,
					RecordStreamType.RECORD);
			writeQueueThread = new QueueThread<>("writeQueueThread", platform.getSelfId(), streamFileWriter,
					recordStreamQueueCapacity);
		}

		this.runningAvgs = runningAvgs;

		runningHashCalculator = new RunningHashCalculatorForStream<>();
		hashCalculator = new HashCalculatorForStream<>(runningHashCalculator);
		hashQueueThread = new QueueThread<>("hashQueueThread", platform.getSelfId(), hashCalculator,
				recordStreamQueueCapacity);

		multiStream = new MultiStream<>(
				enableRecordStreaming ? List.of(hashQueueThread, writeQueueThread) : List.of(hashQueueThread));
		multiStream.setRunningHash(initialHash);
	}

	/**
	 * Is used for unit testing
	 *
	 * @param multiStream
	 * 		the instance which receives {@link RecordStreamObject}s then passes to nextStreams
	 * @param writeQueueThread
	 * 		receives {@link RecordStreamObject}s from multiStream, then passes to streamFileWriter
	 * @param runningAvgs
	 * 		an instance for recording the average value of recordStream queue size
	 */
	RecordStreamManager(final MultiStream<T> multiStream, final QueueThread<T> writeQueueThread,
			final MiscRunningAvgs runningAvgs) {
		this.multiStream = multiStream;
		this.writeQueueThread = writeQueueThread;
		multiStream.setRunningHash(initialHash);
		this.runningAvgs = runningAvgs;
	}

	/**
	 * receives a consensus record from {@link com.hedera.services.legacy.services.state.AwareProcessLogic} each time,
	 * sends it to multiStream which then sends to two queueThread for calculating runningHash and writing to file
	 *
	 * @param recordStreamObject
	 * 		the {@link RecordStreamObject} object to be added
	 * @throws InterruptedException
	 */
	public void addRecordStreamObject(final T recordStreamObject) throws InterruptedException {
		if (!inFreeze) {
			multiStream.add(recordStreamObject);
		}
		runningAvgs.recordStreamQueueSize(getRecordStreamingQueueSize());
	}

	/**
	 * set `inFreeze` to be the given value
	 *
	 * @param inFreeze
	 */
	public void setInFreeze(boolean inFreeze) {
		this.inFreeze = inFreeze;
		LOGGER.info("RecordStream inFreeze is set to be {} ", inFreeze);
		multiStream.close();
	}

	/**
	 * sets initialHash after loading from signed state
	 *
	 * @param initialHash
	 * 		current runningHash of all {@link RecordStreamObject}s
	 */
	public void setInitialHash(final Hash initialHash) {
		this.initialHash = initialHash;
		LOGGER.info("RecordStreamManager::setInitialHash: {}", () -> initialHash);
		multiStream.setRunningHash(initialHash);
	}

	/**
	 * sets startWriteAtCompleteWindow:
	 * it should be set to be true after reconnect, or at state recovering;
	 * it should be set to be false at restart
	 *
	 * @param startWriteAtCompleteWindow
	 * 		whether the writer should not write until the first complete window
	 */
	public void setStartWriteAtCompleteWindow(boolean startWriteAtCompleteWindow) {
		if (streamFileWriter != null) {
			streamFileWriter.setStartWriteAtCompleteWindow(startWriteAtCompleteWindow);
		}
	}

	/**
	 * returns current size of working queue for calculating hash and runningHash
	 *
	 * @return current size of working queue for calculating hash and runningHash
	 */
	int getHashQueueSize() {
		return hashQueueThread.getQueueSize();
	}

	/**
	 * returns current size of working queue for writing to record stream files
	 *
	 * @return current size of working queue for writing to record stream files
	 */
	int getRecordStreamingQueueSize() {
		return writeQueueThread == null ? 0 : writeQueueThread.getQueueSize();
	}

	/**
	 * for unit testing
	 *
	 * @return current multiStream instance
	 */
	MultiStream<T> getMultiStream() {
		return multiStream;
	}

	/**
	 * for unit testing
	 *
	 * @return current TimestampStreamFileWriter instance
	 */
	TimestampStreamFileWriter<T> getStreamFileWriter() {
		return streamFileWriter;
	}

	/**
	 * for unit testing
	 *
	 * @return current HashCalculatorForStream instance
	 */
	HashCalculatorForStream<T> getHashCalculator() {
		return hashCalculator;
	}

	/**
	 * for unit testing
	 *
	 * @return whether freeze period has started
	 */
	boolean getInFreeze() {
		return inFreeze;
	}

	/**
	 * for unit testing
	 *
	 * @return a copy of initialHash
	 */
	Hash getInitialHash() {
		return new Hash(initialHash);
	}
}
