package io.github.supervate.vlog.appender;

import io.github.supervate.vlog.common.Constants;
import io.github.supervate.vlog.common.ThrowableUtils;
import io.github.supervate.vlog.common.Tuple2;
import io.github.supervate.vlog.exception.CreateAppenderException;
import io.github.supervate.vlog.event.LogEvent;
import io.github.supervate.vlog.layout.Layout;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 默认日志输出-文件
 * <p>
 * 支持自动清理/日志文件按天滚动记录.
 *
 * @author supervate
 * @since 2024/04/27
 * <p>
 * All rights Reserved.
 */
@SuppressWarnings({ "ResultOfMethodCallIgnored", "resource", "UnusedReturnValue" })
public class DefaultFileAppender extends AsyncAppender<LogEvent> {

    public static final int MIN_FILE_SIZE = 10 * 1024 * 1024;
    private static final int WRITE_BUFFER_SIZE = 1024;

    private final Layout<LogEvent> layout;
    private final Path directory;
    private final AtomicReference<Tuple2<Path, FileChannel>> logFile;
    private final Lock fileOptionLock;
    private final ScheduledExecutorService scheduledExecutorService;
    /**
     * 默认最长保留七天
     * 注意: 如果可配置,本值最少要保留一天(当天日志不删除).
     */
    private final int logFileRetentionDays;
    /**
     * -1代表不限制大小
     */
    @SuppressWarnings("FieldMayBeFinal")
    private int logFileSizeBytes;

    ThreadLocal<ByteBuffer> logWriteBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(WRITE_BUFFER_SIZE));

    public DefaultFileAppender(Layout<LogEvent> layout, Path directory) {
        this(layout, directory, Constants.DEFAULT_LOG_FILE_RETENTION, Constants.DEFAULT_LOG_FILE_SIZE);
    }

    public DefaultFileAppender(Layout<LogEvent> layout, Path directory, int logFileRetentionDays) {
        this(layout, directory, logFileRetentionDays, Constants.DEFAULT_LOG_FILE_SIZE);
    }

    public DefaultFileAppender(Layout<LogEvent> layout, Path directory, int logFileRetentionDays, int logFileSizeBytes) {
        if (Objects.isNull(directory) || (Files.exists(directory) && !Files.isDirectory(directory))) {
            throw new CreateAppenderException("log directory missing, it will not record any log event");
        }
        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new CreateAppenderException("log directory create failed.", e);
            }
        }
        this.layout = layout;
        this.directory = directory;
        this.logFile = new AtomicReference<>();
        this.fileOptionLock = new ReentrantLock();
        this.scheduledExecutorService = Executors.newScheduledThreadPool(1);
        // <=0则不删除文件
        this.logFileRetentionDays = Math.max(logFileRetentionDays, 0);
        // 最小10m,<=0则不限制.
        this.logFileSizeBytes = logFileSizeBytes <= 0 ? 0 : Math.max(logFileSizeBytes, MIN_FILE_SIZE);
        init();
    }

    private void init() {
        try {
            LocalDateTime now = LocalDateTime.now();
            Tuple2<Path, FileChannel> initLogFile = openLogFile(dateToLogFileName(now), computeFileIndex(now, false));
            logFile.set(initLogFile);
        } catch (IOException e){
            throw new CreateAppenderException(e);
        }
    }

    private int computeFileIndex(LocalDateTime date, boolean increment) throws IOException {
        String todayFileNamePrefix = dateToLogFileName(date);
        return Files
            .list(directory)
            .map(path -> {
                String fileName = path.getFileName().toString();
                int prefixIndex = fileName.lastIndexOf(todayFileNamePrefix);
                if (prefixIndex != -1) {
                    if (fileName.length() == todayFileNamePrefix.length()) {
                        // same file
                        return 0;
                    }
                    // +1 for '-'
                    String indexStr = fileName.substring(prefixIndex + todayFileNamePrefix.length() + 1);
                    try {
                        return Integer.parseInt(indexStr);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            })
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .map(integer -> increment ? integer + 1 : integer)
            .orElse(0);
    }

    @Override
    public boolean start() {
        return super.start();
    }

    @Override
    public boolean stop() {
        scheduledExecutorService.shutdown();
        return super.stop();
    }

    @Override
    public boolean support(LogEvent event) {
        return event != null;
    }

    @Override
    void doAppend(LogEvent event) {
        try {
            String message = layout.format(event);
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            Tuple2<Path, FileChannel> logFile = getLogFile(bytes.length);
            if (Objects.nonNull(logFile)) {
                int len = bytes.length;
                int rem = len;
                while (rem > 0) {
                    int n = Math.min(rem, WRITE_BUFFER_SIZE);
                    logWriteBuffer.get().clear();
                    logWriteBuffer.get().put(bytes, (len - rem), n);
                    logWriteBuffer.get().flip();
                    logFile.getO2().write(logWriteBuffer.get());
                    rem -= n;
                }
            }
        } catch (Exception e) {
            System.err.println(ThrowableUtils.throwableToStr(e));
        }
    }

    private Tuple2<Path, FileChannel> getLogFile(int messageLength) throws IOException {
        String logFileNamePrefix = dateToLogFileName(LocalDateTime.now());
        Tuple2<Path, FileChannel> fileAndChannel;
        while (!isValidLogFile(fileAndChannel = logFile.get(), messageLength)) {
            fileOptionLock.lock();
            try {
                if (isValidLogFile(fileAndChannel = logFile.get(), messageLength)) {
                    return fileAndChannel;
                }
                try {
                    // close the old channel
                    closeFileChannel(logFile.get());
                    LocalDateTime date = LocalDateTime.now();
                    int fileIndex = computeFileIndex(date, true);
                    fileAndChannel = openLogFile(logFileNamePrefix, fileIndex);
                    logFile.set(fileAndChannel);
                    // trigger expired clean task
                    if (logFileRetentionDays > 0) {
                        triggerCleanTask(date);
                    }
                } catch (IOException e) {
                    System.err.println("[DefaultFileAppender] log file create error.");
                    System.err.println(ThrowableUtils.throwableToStr(e));
                    return null;
                }
            } finally {
                fileOptionLock.unlock();
            }
        }
        return fileAndChannel;
    }

    private ScheduledFuture<?> triggerCleanTask(LocalDateTime date) {
        return scheduledExecutorService.schedule(new CleanerTask(date, this), 0, TimeUnit.MILLISECONDS);
    }

    private Tuple2<Path, FileChannel> openLogFile(String logFileNamePrefix, int fileIndex) throws IOException {
        Path path = directory.resolve(logFileNamePrefix + (fileIndex == 0 ? "" : "-" + fileIndex));
        FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        return new Tuple2<>(path, fileChannel);
    }

    private boolean isValidLogFile(Tuple2<Path, FileChannel> pathAndChannel, int messageLength) throws IOException {
        if (Objects.isNull(pathAndChannel)) return false;
        Path path = pathAndChannel.getO1();
        String logFileName = path.getFileName().toString();
        boolean isTodayFile = logFileName.startsWith(dateToLogFileName(LocalDateTime.now()));
        boolean exists = Files.exists(pathAndChannel.getO1());
        boolean sufficient = false;
        if (exists && isTodayFile) {
            // 针对消息体大于当前文件的情况,我们允许它本次将内容追加进去,作为兜底策略.
            sufficient = capacitySufficient(pathAndChannel, messageLength) || isSuperMessage(messageLength);
        }
        return isTodayFile && exists && sufficient;
    }

    private boolean capacitySufficient(Tuple2<Path, FileChannel> pathAndChannel, int messageLength) throws IOException {
        return logFileSizeBytes <= 0 || (pathAndChannel.getO2().size() + messageLength <= logFileSizeBytes);
    }

    private boolean isSuperMessage(int messageLength) {
        return logFileSizeBytes > 0 && messageLength > logFileSizeBytes;
    }

    private void cleanExpiredFiles(LocalDateTime triggerDate) throws IOException {
        System.out.println(
            "[DefaultFileAppender] start clean expired log files,triggerDate: " + triggerDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        );
        // 定时清理过期的日志文件
        fileOptionLock.lock();
        try {
            LocalDateTime expiredTime = triggerDate
                .with(LocalTime.MIN)
                .minusDays(logFileRetentionDays);
            Files.list(directory)
                .map(DefaultFileAppender::mapPathToLogFile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(Tuple2::getO1))
                .forEach(dateAndPath -> {
                    if (!dateAndPath.getO1().isAfter(expiredTime)) {
                        File file = dateAndPath.getO2().toFile();
                        file.delete();
                        System.out.printf("[DefaultFileAppender] expired log file [%s] deleted.%n", file.getName());
                    }
                });
        } finally {
            System.out.println("[DefaultFileAppender] clean expired log files finish.");
            fileOptionLock.unlock();
        }
    }

    private static Optional<Tuple2<LocalDateTime, Path>> mapPathToLogFile(Path path) {
        if (Files.isDirectory(path)) {
            return Optional.empty();
        }
        return logFileNameToDate(path.getFileName().toString()).map(date -> new Tuple2<>(date, path));
    }

    public static Optional<LocalDateTime> logFileNameToDate(String logFileName) {
        try {
            // 2024-04-27 / 2024-04-27-n
            return Optional.of(LocalDateTime.of(
                LocalDate.parse(logFileName.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE),
                LocalTime.MIN
            ));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static String dateToLogFileName(LocalDateTime date) {
        return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private void closeFileChannel(Tuple2<Path, FileChannel> logFile) {
        Optional
            .ofNullable(logFile)
            .map(Tuple2::getO2)
            .filter(AbstractInterruptibleChannel::isOpen)
            .ifPresent(fileChannel -> {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    System.out.println("[DefaultFileAppender] close log file failed.");
                }
            });
    }

    private static class CleanerTask implements Runnable {

        /**
         * expiredDate's date and before will be deleted.
         */
        private final LocalDateTime triggerDate;
        private final DefaultFileAppender defaultFileAppender;

        public CleanerTask(LocalDateTime triggerDate, DefaultFileAppender defaultFileAppender) {
            this.triggerDate = triggerDate;
            this.defaultFileAppender = defaultFileAppender;
        }

        @Override
        public void run() {
            try {
                defaultFileAppender.cleanExpiredFiles(triggerDate);
            } catch (Exception e) {
                System.err.println("[DefaultFileAppender] clean expired log files failed.");
                System.err.println(ThrowableUtils.throwableToStr(e));
            }
        }

    }
}
