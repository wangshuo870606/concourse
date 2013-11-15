/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013 Jeff Nelson, Cinchapi Software Collective
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.cinchapi.concourse.server.engine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.cinchapi.concourse.server.GlobalState;
import org.cinchapi.concourse.server.concurrent.ConcourseExecutors;
import org.cinchapi.concourse.server.io.Byteable;
import org.cinchapi.concourse.server.io.FileSystem;
import org.cinchapi.concourse.server.model.PrimaryKey;
import org.cinchapi.concourse.server.model.Storable;
import org.cinchapi.concourse.server.model.Text;
import org.cinchapi.concourse.server.model.Value;
import org.cinchapi.concourse.server.model.Write;
import org.cinchapi.concourse.thrift.Operator;
import org.cinchapi.concourse.thrift.TObject;
import org.cinchapi.concourse.util.Loggers;
import org.cinchapi.concourse.util.Transformers;
import org.slf4j.Logger;

import com.google.common.base.Throwables;

import static org.cinchapi.concourse.server.engine.Record.loadPrimaryRecord;
import static org.cinchapi.concourse.server.engine.Record.loadSecondaryIndex;
import static org.cinchapi.concourse.server.engine.Record.loadSearchIndex;
import static org.cinchapi.concourse.server.GlobalState.*;

/**
 * The {@code Database} is the permanent {@link PermanentStore} for
 * {@link Write} objects that are initially stored in a {@link Buffer}.
 * <p>
 * When the Database accepts a write, it creates relevant indexes for efficient
 * retrieval, query and search reads.
 * </p>
 * 
 * @author jnelson
 */
public class Database implements PermanentStore {

	/**
	 * Return a {@link Runnable} that will execute the appropriate write
	 * function in {@code record} to store {@code write} in the {@link Database}
	 * .
	 * 
	 * @param record
	 * @param write
	 * @return the appropriate Runnable
	 */
	public static <L extends Byteable, K extends Byteable, V extends Storable> Runnable getWriteRunnable(
			final Record<L, K, V> record, final Write write) {
		return new Runnable() {

			@Override
			public void run() {
				log.debug("Writing {} to {}", write, record);
				if(record instanceof PrimaryRecord) {
					if(write.getType() == Write.Type.ADD) {
						((PrimaryRecord) record).add(write.getKey(),
								write.getValue());
					}
					else if(write.getType() == Write.Type.REMOVE) {
						((PrimaryRecord) record).remove(write.getKey(),
								write.getValue());
					}
					else {
						throw new IllegalArgumentException();
					}
				}
				else if(record instanceof SecondaryIndex) {
					if(write.getType() == Write.Type.ADD) {
						((SecondaryIndex) record).add(write.getValue(),
								write.getRecord());
					}
					else if(write.getType() == Write.Type.REMOVE) {
						((SecondaryIndex) record).remove(write.getValue(),
								write.getRecord());
					}
					else {
						throw new IllegalArgumentException();
					}
				}
				else if(record instanceof SearchIndex) {
					if(write.getType() == Write.Type.ADD) {
						((SearchIndex) record).add(write.getValue(),
								write.getRecord());
					}
					else if(write.getType() == Write.Type.REMOVE) {
						((SearchIndex) record).remove(write.getValue(),
								write.getRecord());
					}
					else {
						throw new IllegalArgumentException();
					}
				}
				else {
					throw new IllegalArgumentException();
				}
			}

		};
	}

	/**
	 * The location where the Database stores data.
	 */
	private final String backingStore;

	/**
	 * A flag to indicate if the Buffer is running or not.
	 */
	private boolean running = false;

	private static final String threadNamePrefix = "database-write-thread";
	private static final Logger log = Loggers.getLogger();

	/**
	 * Construct a Database that is backed by the default location which is in
	 * {@link GlobalState#DATABASE_DIRECTORY}.
	 * 
	 */
	public Database() {
		this(DATABASE_DIRECTORY);
	}

	/**
	 * Construct a Database that is backed by {@link backingStore} directory.
	 * The {@link backingStore} is passed to each {@link Record} as the
	 * {@code parentStore}.
	 * 
	 * @param backingStore
	 */
	public Database(String backingStore) {
		this.backingStore = backingStore;
	}

	@Override
	public void accept(Write write) {
		ConcourseExecutors
				.executeAndAwaitTermination(
						threadNamePrefix,
						Database.getWriteRunnable(
								loadPrimaryRecord(write.getRecord(),
										backingStore), write),
						Database.getWriteRunnable(
								loadSecondaryIndex(write.getKey(), backingStore),
								write), Database.getWriteRunnable(
								loadSearchIndex(write.getKey(), backingStore),
								write));
	}

	@Override
	public Map<Long, String> audit(long record) {
		return loadPrimaryRecord(PrimaryKey.notForStorage(record), backingStore)
				.audit();
	}

	@Override
	public Map<Long, String> audit(String key, long record) {
		return loadPrimaryRecord(PrimaryKey.notForStorage(record), backingStore)
				.audit(Text.fromString(key));
	}

	@Override
	public Set<String> describe(long record) {
		return Transformers.transformSet(
				loadPrimaryRecord(PrimaryKey.notForStorage(record),
						backingStore).describe(), Functions.TEXT_TO_STRING);
	}

	@Override
	public Set<String> describe(long record, long timestamp) {
		return Transformers.transformSet(
				loadPrimaryRecord(PrimaryKey.notForStorage(record),
						backingStore).describe(timestamp),
				Functions.TEXT_TO_STRING);
	}

	@Override
	public Set<TObject> fetch(String key, long record) {
		return Transformers.transformSet(
				loadPrimaryRecord(PrimaryKey.notForStorage(record),
						backingStore).fetch(Text.fromString(key)),
				Functions.VALUE_TO_TOBJECT);
	}

	@Override
	public Set<TObject> fetch(String key, long record, long timestamp) {
		return Transformers.transformSet(
				loadPrimaryRecord(PrimaryKey.notForStorage(record),
						backingStore).fetch(Text.fromString(key), timestamp),
				Functions.VALUE_TO_TOBJECT);
	}

	@Override
	public Set<Long> find(long timestamp, String key, Operator operator,
			TObject... values) {
		return Transformers.transformSet(
				loadSecondaryIndex(Text.fromString(key), backingStore).find(
						timestamp,
						operator,
						Transformers.transformArray(values,
								Functions.TOBJECT_TO_VALUE, Value.class)),
				Functions.PRIMARY_KEY_TO_LONG);
	}

	@Override
	public Set<Long> find(String key, Operator operator, TObject... values) {
		return Transformers.transformSet(
				loadSecondaryIndex(Text.fromString(key), backingStore).find(
						operator,
						Transformers.transformArray(values,
								Functions.TOBJECT_TO_VALUE, Value.class)),
				Functions.PRIMARY_KEY_TO_LONG);
	}

	@Override
	public boolean ping(long record) {
		return loadPrimaryRecord(PrimaryKey.notForStorage(record), backingStore)
				.ping();
	}

	@Override
	public Set<Long> search(String key, String query) {
		return Transformers.transformSet(
				loadSearchIndex(Text.fromString(key), backingStore).search(
						Text.fromString(query)), Functions.PRIMARY_KEY_TO_LONG);
	}

	@Override
	public void start() {
		if(!running) {
			running = true;
			log.info("Database configured to store data in {}", backingStore);
			ConcourseExecutors.executeAndAwaitTermination("Database",
					new RecordLoader(SecondaryIndex.class), new RecordLoader(
							SearchIndex.class));
		}
	}

	@Override
	public boolean verify(String key, TObject value, long record) {
		return loadPrimaryRecord(PrimaryKey.notForStorage(record), backingStore)
				.verify(Text.fromString(key), Value.notForStorage(value));
	}

	@Override
	public boolean verify(String key, TObject value, long record, long timestamp) {
		return loadPrimaryRecord(PrimaryKey.notForStorage(record), backingStore)
				.verify(Text.fromString(key), Value.notForStorage(value),
						timestamp);
	}

	/**
	 * A runnable that traverses the appropriate directory for a record type
	 * under {@code backingStore} and loads the records into memory.
	 * 
	 * @author jnelson
	 */
	private final class RecordLoader implements Runnable {

		private final Class<?> clazz;
		private final ExecutorService executor = ConcourseExecutors
				.newCachedThreadPool("StartupRecordLoader");

		/**
		 * Construct a new instance.
		 * 
		 * @param clazz
		 */
		public RecordLoader(Class<?> clazz) {
			this.clazz = clazz;
		}

		@Override
		public void run() {
			log.info("Loading existing {} files...", clazz.getSimpleName());
			String label = Record.getLabel(clazz);
			Path path = Paths.get(backingStore, label);
			FileSystem.mkdirs(path.toString());
			process(path);
			executor.shutdown();
			while (!executor.isTerminated()) {
				continue;
			}
		}

		/**
		 * Process the records in {@code path}.
		 * 
		 * @param path
		 */
		private void process(Path path) {
			try {
				DirectoryStream<Path> paths = Files.newDirectoryStream(path);
				for (final Path p : paths) {
					if(Files.isDirectory(p)) {
						process(p);
					}
					else {
						executor.execute(new Runnable() {

							@Override
							public void run() {
								Record.open(clazz, p.toString());
							}

						});

					}
				}
				paths.close();
			}
			catch (IOException e) {
				throw Throwables.propagate(e);
			}
		}

	}
}