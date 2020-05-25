package VASSAL.launch;

import VASSAL.Info;
import VASSAL.i18n.TranslateVassalWindow;
import VASSAL.tools.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

public class ModuleManagerMain {

  private static final Logger logger =
    LoggerFactory.getLogger(ModuleManagerMain.class);

  private static LaunchRequest parseArguments(String[] args) {
    LaunchRequest result = null;
    try {
      result = LaunchRequest.parseArgs(args);
    }
    catch (LaunchRequestException e) {
// FIXME: should be a dialog...
      System.err.println("VASSAL: " + e.getMessage());
      System.exit(1);
    }

    return result;
  }

  private static void showTranslationWindowInTranslationMode() {
    SwingUtilities.invokeLater(() -> {
      // FIXME: does this window exit on close?
      new TranslateVassalWindow(null).setVisible(true);
    });
  }

  private static void acquireExclusiveLock(RandomAccessFile file) throws IOException {
    try {
      file.getChannel().lock();
    }
    catch (OverlappingFileLockException e) {
      throw new IOException(e);
    }
  }

  /**
   * Tries to acquire a lock on the lockfile, keeping the {@link java.io.OutputStream} open.
   *
   * Note: We purposely keep the OutputStream on the lockfile open in the case where we are the
   * server, because closing it will release the lock.
   */
  private static LockAndStream tryLock(File file) throws IOException {
    final FileLock lock;
    final FileOutputStream os = new FileOutputStream(file);
    try {
      lock = os.getChannel().tryLock();
    }
    catch (OverlappingFileLockException e) {
      throw new IOException(e);
    }

    return new LockAndStream(lock, os);
  }

  private static PortAndKey doRequestServer(RandomAccessFile keyRaf, LockAndStream lockAndStream) throws IOException {
    // bind to an available port on the loopback device
    final ServerSocket serverSocket =
      new ServerSocket(0, 0, InetAddress.getByName(null));

    // write the port number where we listen to the key file
    final int port = serverSocket.getLocalPort();
    keyRaf.writeInt(port);

    // create new security key and write it to the key file
    final long key = (long) (Math.random() * Long.MAX_VALUE);
    keyRaf.writeLong(key);

    // create a new Module Manager
    new ModuleManager(serverSocket, key, lockAndStream.getStream(), lockAndStream.getLock());

    return new PortAndKey(port, key);
  }

  private static PortAndKey doRequestClient(FileOutputStream lout, RandomAccessFile keyRaf) throws IOException {
    lout.close();

    // read the port number we will connect to from the key file
    final int port = keyRaf.readInt();

    // read the security key from the key file
    final long key = keyRaf.readLong();

    return new PortAndKey(port, key);
  }

  private static PortAndKey determinePortAndKey(File keyfile, File lockfile) {
    try (RandomAccessFile keyRaf = new RandomAccessFile(keyfile, "rw")) {

      // acquire an exclusive lock on the key file
      acquireExclusiveLock(keyRaf);

      // determine whether we are the server or a client
      final LockAndStream lockAndStream = tryLock(lockfile);

      final boolean areWeRequestServer = lockAndStream.getLock() != null;
      PortAndKey result = areWeRequestServer ?
        doRequestServer(keyRaf, lockAndStream)
        : doRequestClient(lockAndStream.getStream(), keyRaf);

      return result;

      // lock on the keyfile is released by try-with-resources
    }
    catch (IOException e) {
      // FIXME: should be a dialog...
      System.err.println("VASSAL: IO error");
      e.printStackTrace();
      System.exit(1);
    }

    // should never happen, if we cant determine port/key we System.exit() beforehand
    return null;
  }

  private static void bootstrapModuleManager(String[] args) {
    final LaunchRequest lr = parseArguments(args);

    // do this before the graphics subsystem fires up or it won't stick
    System.setProperty("swing.boldMetal", "false");

    if (lr.mode == LaunchRequest.Mode.TRANSLATE) {
      showTranslationWindowInTranslationMode();
      return;
    }

    //
    // How we start exactly one request server:
    //
    // To ensure that exactly one process acts as the request server, we
    // acquire a lock on the ~/VASSAL/key file, and then attempt to acquire
    // a lock on the ~/VASSAL/lock file. If we cannot lock ~/VASSAL/lock,
    // then there is already a server running; in that case, we read the
    // port number and security key from ~/VASSAL/key. If we can lock
    // ~/VASSAL/lock, then we start the server, write the port number and
    // key to ~/VASSAL/key, and continue to hold the lock on ~/VASSAL/lock.
    // Finally, we unlock ~/VASSAL/key and proceed to act as a client,
    // sending requests over localhost:port using the security key.
    //
    // The advantages of this method are:
    //
    // (1) No race conditions between processes started at the same time.
    // (2) No port collisions, because we don't use a predetermined port.
    //

    final File keyfile = new File(Info.getConfDir(), "key");
    final File lockfile = new File(Info.getConfDir(), "lock");

    PortAndKey portAndKey = determinePortAndKey(keyfile, lockfile);

    lr.key = portAndKey.getKey();

    // pass launch parameters on to the ModuleManager via the socket
    try (Socket clientSocket =
           new Socket((String) null, portAndKey.getPort());
         ObjectOutputStream out =
           new ObjectOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()));
         InputStream in = clientSocket.getInputStream()
    ) {
      out.writeObject(lr);
      out.flush();
      IOUtils.copy(in, System.err);
    }
    catch (IOException e) {
      // FIXME: should be a dialog...
      System.err.println("VASSAL: Problem with socket on port " + portAndKey.getPort());
      e.printStackTrace();
      System.exit(1);
    }
  }

  public static void main(String[] args) {
    // FIXME: We need to catch more exceptions in main() and then exit in
    // order to avoid situations where the main thread ends due to an uncaught
    // exception, but there are other threads still running, and so VASSAL
    // does not quit. For example, this can happen if an IllegalArgumentException
    // is thrown here...
    try {
      bootstrapModuleManager(args);
    } catch (Exception e) {
      logger.error("Exception in main thread", e);
      // FIXME: stop other threads and shutdown gracefully?
    }
  }

  private static class PortAndKey {
    private final int port;
    private final long key;

    public PortAndKey(int port, long key) {
      this.port = port;
      this.key = key;
    }

    public int getPort() {
      return port;
    }

    public long getKey() {
      return key;
    }
  }

  private static class LockAndStream {
    private final FileLock lock;
    private final FileOutputStream stream;

    public LockAndStream(FileLock lock, FileOutputStream stream) {
      this.lock = lock;
      this.stream = stream;
    }

    public FileLock getLock() {
      return lock;
    }

    public FileOutputStream getStream() {
      return stream;
    }
  }
}
