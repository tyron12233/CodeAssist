package dev.ide.index

/**
 * index-api — the SPI for the on-device indexing subsystem.
 *
 * Every index is an [IndexExtension] registered on the [INDEX_EP] extension point; the framework owns
 * the engine (dictionary, postings, trigrams, persistence, queries) and an extension only declares
 * *what* to index and *how to (de)serialize* its keys/values. Resolution and completion query
 * [IndexService] — they never maintain their own indexes.
 */

@JvmInline
value class IndexId(val value: String)