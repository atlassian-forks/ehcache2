/**
 *  Copyright 2003-2009 Terracotta, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.sf.ehcache.store.compound.factories;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.RandomAccessFile;

import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.Element;
import net.sf.ehcache.store.compound.ElementSubstitute;
import net.sf.ehcache.store.compound.ElementSubstituteFactory;
import net.sf.ehcache.util.MemoryEfficientByteArrayOutputStream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * A mock-up of a on-disk element proxy factory.
 * 
 * @author Chris Dennis
 */
abstract class DiskStorageFactory<T extends ElementSubstitute> implements ElementSubstituteFactory<T> {

    private static final String AUTO_DISK_PATH_DIRECTORY_PREFIX = "ehcache_auto_created";
    private static final int SERIALIZATION_CONCURRENCY_DELAY = 250;
    private static final int SHUTDOWN_GRACE_PERIOD = 60;
    
    private static final Logger LOG = LoggerFactory.getLogger(DiskStorageFactory.class.getName());

    private final BlockingQueue<Runnable> diskQueue = new LinkedBlockingQueue<Runnable>(); 
    /**
     * Executor service used to write elements to disk
     */
    private final ExecutorService diskWriter = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, diskQueue);
    
    private final File             file;
    private final RandomAccessFile data;

    private final Collection<DiskMarker> freeChunks = new ConcurrentLinkedQueue<DiskMarker>();

    public DiskStorageFactory(File dataFile) {
        this.file = dataFile;
        try {
            data = new RandomAccessFile(file, "rw");
        } catch (FileNotFoundException e) {
            throw new CacheException(e);
        }
    }
    
    protected void shutdown() throws IOException {
        diskWriter.shutdown();
        for (int i = 0; i < SHUTDOWN_GRACE_PERIOD; i++) {
            try {
                if (diskWriter.awaitTermination(1, TimeUnit.SECONDS)) {
                    break;
                } else {
                    LOG.info("Waited " + (i + 1) + " seconds for shutdown");
                }
            } catch (InterruptedException e) {
                LOG.warn("Received exception while waiting for shutdown", e);
            }
        }
        data.close();
    }
    
    protected void delete() {
        file.delete();
        freeChunks.clear();
        if (file.getAbsolutePath().contains(AUTO_DISK_PATH_DIRECTORY_PREFIX)) {
            //try to delete the auto_createtimestamp directory. Will work when the last Disk Store deletes
            //the last files and the directory becomes empty.
            File dataDirectory = file.getParentFile();
            if (dataDirectory != null && dataDirectory.exists()) {
                if (dataDirectory.delete()) {
                    LOG.debug("Deleted directory " + dataDirectory.getName());
                }
            }

        }
    }
    
    protected <U> Future<U> schedule(Callable<U> call) {
        return diskWriter.submit(call);
    }
    
    protected Element read(DiskMarker marker) throws IOException, ClassNotFoundException {
        final byte[] buffer = new byte[marker.getSize()];
        synchronized (data) {
            // Load the element
            data.seek(marker.getPosition());
            data.readFully(buffer);
        }
        
        ObjectInputStream objstr = new ObjectInputStream(new ByteArrayInputStream(buffer)) {
            /**
             * Overridden because of:
             * Bug 1324221 ehcache DiskStore has issues when used in Tomcat
             */
            @Override
            protected Class resolveClass(ObjectStreamClass clazz) throws ClassNotFoundException, IOException {
                try {
                    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                    return Class.forName(clazz.getName(), false, classLoader);
                } catch (ClassNotFoundException e) {
                    // Use the default as a fallback because of
                    // bug 1517565 - DiskStore loadElementFromDiskElement
                    return super.resolveClass(clazz);
                }
            }
        };
        
        try {
            return (Element) objstr.readObject();
        } finally {
            objstr.close();
        }
    }

    protected DiskMarker write(Element element) throws IOException {
        MemoryEfficientByteArrayOutputStream buffer = serializeElement(element);
        int bufferLength = buffer.size();
        DiskMarker marker = alloc(element, bufferLength);
        // Write the record
        synchronized (data) {
            data.seek(marker.getPosition());
            data.write(buffer.toByteArray(), 0, bufferLength);
        }
        return marker;
    }

    private MemoryEfficientByteArrayOutputStream serializeElement(Element element) throws IOException {
        // try two times to Serialize. A ConcurrentModificationException can occur because Java's serialization
        // mechanism is not threadsafe and POJOs are seldom implemented in a threadsafe way.
        // e.g. we are serializing an ArrayList field while another thread somewhere in the application is appending to it.
        // The best we can do is try again and then give up.
        ConcurrentModificationException exception = null;
        for (int retryCount = 0; retryCount < 2; retryCount++) {
            try {
                return MemoryEfficientByteArrayOutputStream.serialize(element);
            } catch (ConcurrentModificationException e) {
                exception = e;
                try {
                    // wait for the other thread(s) to finish
                    MILLISECONDS.sleep(SERIALIZATION_CONCURRENCY_DELAY);
                } catch (InterruptedException e1) {
                    //no-op
                }
            }
        }
        throw exception;
    }

    private DiskMarker alloc(Element element, int size) throws IOException {
        //check for a matching chunk
        for (DiskMarker marker : freeChunks) {
            if (marker.getCapacity() >= size) {
                freeChunks.remove(marker);
                return new DiskMarker(marker, element.getObjectKey(), size, element.getHitCount(), element.getExpirationTime());
            }
        }
        
        //extend file
        long position;
        synchronized (data) {
            position = data.length();
            data.setLength(position + size);
        }
        return new DiskMarker(element.getObjectKey(), position, size, element.getHitCount(), element.getExpirationTime());
    }
    
    protected void free(DiskMarker marker) {
        freeChunks.add(marker);
    }

    public boolean bufferFull() {
        return diskQueue.size() > 10000;
    }
    
    /**
     * DiskMarker instances point to the location of their
     * associated serialized Element instance.
     */
    class DiskMarker implements ElementSubstitute {
        
        private final Object key;
        
        private final long position;
        private final int capacity;
        private final int size;
        
        private final long hitCount;
        private final long expiry;
        
        public DiskMarker(Object key, long position, int size, long hitCount, long expiry) {
            this(key, position, size, size, hitCount, expiry);
        }
        
        public DiskMarker(DiskMarker from, Object key, int size, long hitCount, long expiry) {
            this(key, from.getPosition(), from.getCapacity(), size, hitCount, expiry);
        }
        
        private DiskMarker(Object key, long position, int capacity, int size, long hitCount, long expiry) {
            this.key = key;
            this.position = position;
            this.capacity = capacity;
            this.size = size;
            
            this.hitCount = hitCount;
            this.expiry = expiry;
        }

        public Object getKey() {
            return key;
        }
        
        public long getPosition() {
            return position;
        }
        
        public int getSize() {
            return size;
        }

        public int getCapacity() {
            return capacity;
        }
        
        public DiskStorageFactory getFactory() {
            return DiskStorageFactory.this;
        }
    }
}
