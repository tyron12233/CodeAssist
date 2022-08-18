package com.tyron.common.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Used to store debouncers and retrieve them according to their call-site.
 * Useful when debouncing many different methods, and you don't want to store
 * each DebounceExecutor in its own field.
 * 
 * @param <K>
 *            the type of key that uniquely identifies debounced methods.
 */
public class DebouncerStore<K> {

	public static final DebouncerStore<String> DEFAULT = new DebouncerStore<>();

	private final Map<K, DebounceExecutor> store = new HashMap<K, DebounceExecutor>();

	public boolean hasDebouncer(K m) {
		return store.containsKey(m);
	}

	public DebounceExecutor registerDebouncer(K m) {
		store.put(m, new DebounceExecutor());
		return getDebouncer(m);
	}

	public DebounceExecutor getDebouncer(K m) {
		return store.get(m);
	}

	/**
	 * Creates a debouncer for this key if none alreay exists, or return the
	 * existing one.
	 */
	public DebounceExecutor registerOrGetDebouncer(K m) {
		if (!hasDebouncer(m)) {
			registerDebouncer(m);
		}

		return getDebouncer(m);
	}
}