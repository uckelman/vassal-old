/*
 * $Id$
 *
 * Copyright (c) 2000-2008 by Rodney Kinney, Joel Uckelman
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, copies are available
 * at http://www.opensource.org.
 */
package VASSAL.launch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileLock;

import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import VASSAL.Info;
import VASSAL.build.module.metadata.AbstractMetaData;
import VASSAL.build.module.metadata.MetaDataFactory;
import VASSAL.build.module.metadata.SaveMetaData;
import VASSAL.configure.IntConfigurer;
import VASSAL.configure.LongConfigurer;
import VASSAL.i18n.Resources;
import VASSAL.preferences.Prefs;
import VASSAL.tools.ErrorDialog;
import VASSAL.tools.ThrowableUtils;
import VASSAL.tools.io.IOUtils;
import VASSAL.tools.io.ZipArchive;
import VASSAL.tools.logging.LoggedOutputStream;
import VASSAL.tools.menu.MacOSXMenuManager;
import VASSAL.tools.menu.MenuBarProxy;
import VASSAL.tools.menu.MenuManager;

/**
 * Tracks recently-used modules and builds the main GUI window for
 * interacting with modules.
 *
 * @author rodneykinney
 * @since 3.1.0
 */
public class ModuleManager {
  private static final Logger logger =
    LoggerFactory.getLogger(ModuleManager.class);

  private static final String NEXT_VERSION_CHECK = "nextVersionCheck";

  public static final String MAXIMUM_HEAP = "maximumHeap"; //$NON-NLS-1$
  public static final String INITIAL_HEAP = "initialHeap"; //$NON-NLS-1$

  private static ModuleManager instance = null;

  public static ModuleManager getInstance() {
    return instance;
  }

  private final long key;

  private final FileOutputStream lout;
  private final FileLock lock;

  public ModuleManager(ServerSocket serverSocket, long key,
                       FileOutputStream lout, FileLock lock)
                                                           throws IOException {

    if (instance != null) throw new IllegalStateException();
    instance = this;

    this.key = key;

    // we hang on to these to prevent the lock from being lost
    this.lout = lout;
    this.lock = lock;

    // truncate the errorLog
    final File errorLog = new File(Info.getHomeDir(), "errorLog");
    new FileOutputStream(errorLog).close();

    final StartUp start = SystemUtils.IS_OS_MAC_OSX ?
      new ModuleManagerMacOSXStartUp() : new StartUp();

    start.startErrorLog();

    // log everything which comes across our stderr
    System.setErr(new PrintStream(new LoggedOutputStream(), true));

    Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler());

    start.initSystemProperties();

    // try to migrate old preferences if there are no current ones
    final File pdir = Info.getPrefsDir();
    if (!pdir.exists()) {
      // Check the 3.2.0 through 3.2.7 location
      File pzip = new File(Info.getHomeDir(), "Preferences");
      if (!pzip.exists()) {
        // Check the pre-3.2 location.
        pzip = new File(System.getProperty("user.home"), "VASSAL/Preferences");
      }

      if (pzip.exists()) {
        FileUtils.forceMkdir(pdir);

        final byte[] buf = new byte[4096];

        try {
          try (ZipArchive za = new ZipArchive(pzip)) {
            for (String f : za.getFiles()) {
              final File ofile = new File(
                pdir, "VASSAL".equals(f) ? "V_Global" : Prefs.sanitize(f)
              );

              try (InputStream in = za.getInputStream(f);
                   OutputStream out = new FileOutputStream(ofile)) {
                IOUtils.copy(in, out, buf);
              }
            }
          }
        }
        catch (IOException e) {
          logger.error("Failed to convert legacy preferences file.", e);
        }
      }
    }

    if (SystemUtils.IS_OS_MAC_OSX) new MacOSXMenuManager();
    else new ModuleManagerMenuManager();

    SwingUtilities.invokeLater(this::launch);

    // ModuleManagerWindow.getInstance() != null now, so listen on the socket
    final Thread socketListener = new Thread(
      new SocketListener(serverSocket), "socket listener");
    socketListener.setDaemon(true);
    socketListener.start();

    final Prefs globalPrefs = Prefs.getGlobalPrefs();

    // determine when we should next check on the current version of VASSAL
    final LongConfigurer nextVersionCheckConfig =
      new LongConfigurer(NEXT_VERSION_CHECK, null, -1L);
    globalPrefs.addOption(null, nextVersionCheckConfig);

    long nextVersionCheck = nextVersionCheckConfig.getLongValue(-1L);
    if (nextVersionCheck < System.currentTimeMillis()) {
        new UpdateCheckRequest().execute();
    }

    // set the time for the next version check
    if (nextVersionCheck == -1L) {
      // this was our first check; randomly check after 0-10 days to
      // to spread version checks evenly over a 10-day period
      nextVersionCheck = System.currentTimeMillis() +
                         (long) (Math.random() * 10 * 86400000);
    }
    else {
      // check again in 10 days
      nextVersionCheck += 10 * 86400000;
    }

    nextVersionCheckConfig.setValue(nextVersionCheck);

// FIXME: the importer heap size configurers don't belong here
    // the initial heap size for the module importer
    final IntConfigurer initHeapConf = new IntConfigurer(
      INITIAL_HEAP,
      Resources.getString("GlobalOptions.initial_heap"),  //$NON-NLS-1$
      256
    );
    globalPrefs.addOption("Importer", initHeapConf);

    // the maximum heap size for the module importer
    final IntConfigurer maxHeapConf = new IntConfigurer(
      MAXIMUM_HEAP,
      Resources.getString("GlobalOptions.maximum_heap"),  //$NON-NLS-1$
      512
    );
    globalPrefs.addOption("Importer", maxHeapConf);
  }

  public void shutDown() throws IOException {
    lock.release();
    lout.close();
  }

  private class SocketListener implements Runnable {
    private final ServerSocket serverSocket;

    public SocketListener(ServerSocket serverSocket) {
      this.serverSocket = serverSocket;
    }

    @Override
    public void run() {
      try (serverSocket) {
        while (true) {
          try (Socket clientSocket = serverSocket.accept();
               ObjectInputStream in =
                 new ObjectInputStream(new BufferedInputStream(clientSocket.getInputStream()));
               PrintStream out =
                 new PrintStream(new BufferedOutputStream(clientSocket.getOutputStream()))
          ) {
            final String message = execute(in.readObject());
            in.close();
            clientSocket.close();

            if (message == null || clientSocket.isClosed()) continue;

            out.println(message);
          }
          catch (IOException e) {
            ErrorDialog.showDetails(
              e,
              ThrowableUtils.getStackTrace(e),
              "Error.socket_error"
            );
          }
          catch (ClassNotFoundException e) {
            ErrorDialog.bug(e);
          }
        }
      }
      catch (IOException e) {
        logger.error("ServerSocket threw an Exception", e);
      }
    }
  }

  protected void launch() {
    logger.info("Manager");
    final ModuleManagerWindow window = ModuleManagerWindow.getInstance();
    window.setVisible(true);

    final boolean isFirstTime = !Info.getPrefsDir().exists();

    if (isFirstTime) new FirstTimeDialog(window).setVisible(true);
  }

  protected String execute(Object req) {
    if (req instanceof LaunchRequest) {
      final LaunchRequest lr = (LaunchRequest) req;

      if (lr.key != key) {
// FIXME: translate
        return "incorrect key";
      }

      final LaunchRequestHandler handler = new LaunchRequestHandler(lr);
      try {
        SwingUtilities.invokeAndWait(handler);
      }
      catch (InterruptedException e) {
        return "interrupted";   // FIXME
      }
      catch (InvocationTargetException e) {
        ErrorDialog.bug(e);
        return null;
      }

      return handler.getResult();
    }
    else {
      return "unrecognized command";  // FIXME
    }
  }

  private static class LaunchRequestHandler implements Runnable {
    private final LaunchRequest lr;
    private String result;

    public LaunchRequestHandler(LaunchRequest lr) {
      this.lr = lr;
    }

    @Override
    public void run() {
      result = handle();
    }

    public String getResult() {
      return result;
    }

    private String handle() {
      final ModuleManagerWindow window = ModuleManagerWindow.getInstance();

      switch (lr.mode) {
      case MANAGE:
        window.toFront();
        break;
      case LOAD:
        if (Player.LaunchAction.isEditing(lr.module))
          return "module open for editing";   // FIXME

        if (lr.module == null && lr.game != null) {
          // attempt to find the module for the saved game or log
          final AbstractMetaData data = MetaDataFactory.buildMetaData(lr.game);
          if (data instanceof SaveMetaData) {
            // we found save metadata
            final String moduleName = ((SaveMetaData) data).getModuleName();
            if (moduleName != null && moduleName.length() > 0) {
              // get the module file by module name
              lr.module = window.getModuleByName(moduleName);
            }
            else {
              // this is a pre 3.1 save file, can't tell the module name
// FIXME: show some error here
              return "cannot find module";
            }
          }
        }

        if (lr.module == null) {
          return "cannot find module";
// FIXME: show some error here
        }
        else if (lr.game == null) {
          new Player.LaunchAction(window, lr.module).actionPerformed(null);
        }
        else {
          new Player.LaunchAction(
            window, lr.module, lr.game).actionPerformed(null);
        }
        break;
      case EDIT:
        if (Editor.LaunchAction.isInUse(lr.module))
          return "module open for play";      // FIXME
        if (Editor.LaunchAction.isEditing(lr.module))
          return "module open for editing";   // FIXME

        new Editor.LaunchAction(window, lr.module).actionPerformed(null);
        break;
      case IMPORT:
        new Editor.ImportLaunchAction(
          window, lr.importFile).actionPerformed(null);
        break;
      case NEW:
        new Editor.NewModuleLaunchAction(window).actionPerformed(null);
        break;
      case EDIT_EXT:
        return "not yet implemented";   // FIXME
      case NEW_EXT:
        return "not yet implemented";   // FIXME
      default:
        return "unrecognized mode";     // FIXME
      }

      return null;
    }
  }

  private static class ModuleManagerMenuManager extends MenuManager {
    private final MenuBarProxy menuBar = new MenuBarProxy();

    @Override
    public JMenuBar getMenuBarFor(JFrame fc) {
      return (fc instanceof ModuleManagerWindow) ? menuBar.createPeer() : null;
    }

    @Override
    public MenuBarProxy getMenuBarProxyFor(JFrame fc) {
      return (fc instanceof ModuleManagerWindow) ? menuBar : null;
    }
  }
}
