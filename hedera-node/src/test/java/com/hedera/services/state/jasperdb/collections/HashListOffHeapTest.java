package com.hedera.services.state.jasperdb.collections;

public class HashListOffHeapTest extends HashListHeapTest {

    @Override
    protected HashList createHashList() {
        return new HashListOffHeap();
    }
}
