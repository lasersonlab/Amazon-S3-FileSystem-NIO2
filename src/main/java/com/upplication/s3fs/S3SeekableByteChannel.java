package com.upplication.s3fs;

import static java.lang.String.format;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.tika.Tika;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;

public class S3SeekableByteChannel implements SeekableByteChannel {

	private S3Path path;
	private Set<? extends OpenOption> options;
	private SeekableByteChannel seekable;
	private Path tempFile;

	public S3SeekableByteChannel(S3Path path, Set<? extends OpenOption> options) throws IOException {
		this.path = path;
		this.options = Collections.unmodifiableSet(new HashSet<>(options));
		String key = path.getKey();
		boolean existed = path.getFileSystem().provider().exists(path);

        if (existed && this.options.contains(StandardOpenOption.CREATE_NEW))
            throw new FileAlreadyExistsException(format("target already exists: %s", path));

		tempFile = Files.createTempFile("temp-s3-", key.replaceAll("/", "_"));
		boolean removeTempFile = true;
		try {
			if (existed) {
				try (S3Object object = path.getFileSystem()
                                           .getClient()
                                           .getObject(path.getFileStore().getBucket().getName(), key)) {
					Files.copy(object.getObjectContent(), tempFile, StandardCopyOption.REPLACE_EXISTING); 
				}
			}

			Set<? extends OpenOption> seekOptions = new HashSet<>(this.options);
			seekOptions.remove(StandardOpenOption.CREATE_NEW);
			seekable = Files.newByteChannel(tempFile, seekOptions);
			removeTempFile = false;
		} finally {
			if (removeTempFile) {
				Files.deleteIfExists(tempFile);
			}
		}
	}

	@Override
	public boolean isOpen() {
		return seekable.isOpen();
	}

	@Override
	public void close() throws IOException {
		try {
			if (!seekable.isOpen())
				return;
			seekable.close();
			if (options.contains(StandardOpenOption.DELETE_ON_CLOSE)) {
                path.getFileSystem().provider().delete(path);
				return;
			}
			// upload the content where the seekable ends (close)
			InputStream stream = null;
			try {
				stream = new BufferedInputStream(Files.newInputStream(tempFile));
				ObjectMetadata metadata = new ObjectMetadata();
				metadata.setContentLength(Files.size(tempFile));
				metadata.setContentType(new Tika().detect(stream, path.getFileName().toString()));

                String bucket = path.getFileStore().name();
                String key = path.getKey();
                path.getFileSystem().getClient().putObject(bucket, key, stream, metadata);
			} finally {
				if(stream != null)
					stream.close();
			}
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Override
	public int write(ByteBuffer src) throws IOException {
		return seekable.write(src);
	}

	@Override
	public SeekableByteChannel truncate(long size) throws IOException {
		return seekable.truncate(size);
	}

	@Override
	public long size() throws IOException {
		return seekable.size();
	}

	@Override
	public int read(ByteBuffer dst) throws IOException {
		return seekable.read(dst);
	}

	@Override
	public SeekableByteChannel position(long newPosition) throws IOException {
		return seekable.position(newPosition);
	}

	@Override
	public long position() throws IOException {
		return seekable.position();
	}
}