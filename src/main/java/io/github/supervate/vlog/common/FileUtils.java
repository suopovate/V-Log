package io.github.supervate.vlog.common;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 文件工具类
 *
 * @author supervate
 * @since 2024/04/27
 * <p>
 * All rights Reserved.
 */
public class FileUtils {

    public static void deleteDirectoryRecursively(Path directoryPath) throws IOException {
        if (!Files.isDirectory(directoryPath)) {
            return;
        }
        Files.walkFileTree(directoryPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
