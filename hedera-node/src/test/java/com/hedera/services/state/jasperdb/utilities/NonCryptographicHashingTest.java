package com.hedera.services.state.jasperdb.utilities;

import com.hedera.services.state.jasperdb.files.HalfDiskHashMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class NonCryptographicHashingTest {
    private static final int ENTRIES_PER_BUCKET = 60; // typical value
    private static final double BIG_CONTRACT_PERCENT = 0.1;
    private static final double HUGE_CONTRACT_PERCENT = 0.01;
    private enum ContractSize{
        Small(1420), // 100Kb
        Big(146_000), // 10MB
        Huge(1_460_000); // 100MB
        public final int keyValueCount;

        ContractSize(int keyValueCount) {
            this.keyValueCount = keyValueCount;
        }
    };

    @ParameterizedTest
    @ValueSource(longs = {10_000, 1_000_000, 500_000_000})// takes too long with a billion
    public void smartContractKeyTest(long mapSize) {
        // for smart contracts we have two data items combined, the first is a long for the contract address and the
        // second is an uint256 for contract storage index. Contract addresses will generally be sequential numbers. The
        // storage indexes could be sequential or sequential at random offsets, so we will test both starting with sequential.
        final int numOfBuckets = computeNumberOfBuckets(mapSize);
        final int[] bucketCounts = new int[numOfBuckets];
        Random random = new Random(123456); 
        long contractAddress = 100;
        long keyValueCount = 0;
        ContractSize contractSize = ContractSize.Small;
        int smallCount = 0;
        int bigCount = 0;
        int hugeCount = 0;
        for (int i = 0; i < mapSize; i++) {
            if (keyValueCount >= contractSize.keyValueCount) {
                // start a new contract
                contractAddress ++;
                keyValueCount = 0;
                final double randomNum = random.nextDouble();
                contractSize = randomNum < HUGE_CONTRACT_PERCENT ? ContractSize.Huge :
                        randomNum < BIG_CONTRACT_PERCENT ? ContractSize.Big : ContractSize.Small;
                if (contractSize == ContractSize.Small) smallCount ++;
                if (contractSize == ContractSize.Big) bigCount ++;
                if (contractSize == ContractSize.Huge) hugeCount ++;
            }
            // create a new hash
            final int hashCode = NonCryptographicHashing.hash32(contractAddress,0,0,0,keyValueCount);
            // find the bucket and add one to its count
            final int bucket = computeBucketIndex(numOfBuckets, hashCode);
            bucketCounts[bucket] ++;
            // increment keyValueCount
            keyValueCount ++;
        }
        // print counts
        System.out.println("smallCount = " + smallCount);
        System.out.println("bigCount = " + bigCount);
        System.out.println("hugeCount = " + hugeCount);
        // now check all the bucket counts
        final var stats = IntStream.of(bucketCounts).summaryStatistics();
        System.out.println("stats = " + stats);
        // we should at least overflow less than once
        assertTrue(stats.getMax() < (ENTRIES_PER_BUCKET*2),"At least one bucket over flowed");
    }

    /**
     * Compute the number of buckets based on code from HalfDiskHashMap
     */
    private int computeNumberOfBuckets(long mapSize) {
        final int minimumBuckets = (int)Math.ceil(((double)mapSize/ HalfDiskHashMap.LOADING_FACTOR)/ ENTRIES_PER_BUCKET);
        return Math.max(4096,Integer.highestOneBit(minimumBuckets)*2); // nearest greater power of two with a min of 4096
    }

    /**
     * Computes which bucket a key with the given hash falls. Depends on the fact the numOfBuckets is a power of two.
     * Based on same calculation that is used in java HashMap.
     *
     * @param numOfBuckets the number of buckets
     * @param keyHash the int hash for key
     * @return the index of the bucket that key falls in
     */
    private int computeBucketIndex(int numOfBuckets, int keyHash) {
        return (numOfBuckets-1) & keyHash;
    }
}
