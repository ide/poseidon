package edu.berkeley.poseidon.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Iterator;

public class FileInputStreamIterator implements Iterator<FileInputStream> {

    private final Iterator<? extends File> files;

    public FileInputStreamIterator(Iterable<? extends File> files) {
        this(files.iterator());
    }

    public FileInputStreamIterator(Iterator<? extends File> files) {
        this.files = files;
    }

    @Override
    public boolean hasNext() {
        return files.hasNext();
    }

    @Override
    public FileInputStream next() {
        try {
            return new FileInputStream(files.next());
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove() is not supported");
    }
}
