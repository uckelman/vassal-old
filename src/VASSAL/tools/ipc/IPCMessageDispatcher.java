package VASSAL.tools.ipc;

import java.io.IOException;
import java.io.ObjectOutput;
import java.util.concurrent.BlockingQueue;

public class IPCMessageDispatcher implements Runnable {

  protected final BlockingQueue<IPCMessage> queue;
  protected final ObjectOutput out;

  public IPCMessageDispatcher(BlockingQueue<IPCMessage> queue,
                              ObjectOutput out) {
    this.queue = queue;
    this.out = out;
  }

  @Override
  public void run() {
    IPCMessage msg;

    try (out) {
      do {
        msg = queue.take();
        out.writeObject(msg);
        out.flush();
      } while (!(msg instanceof Fin));
    }
    catch (IOException | InterruptedException e) {
// FIXME
      e.printStackTrace();
    }
  }
}
