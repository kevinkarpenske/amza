package com.jivesoftware.os.amza.api.value;

/**
 *
 * @author jonathan.colt
 */
public interface KeyValueStream<K, V> {

    boolean keyValue(K key, V value) throws Exception;

}