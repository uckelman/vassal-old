package VASSAL.build.module.dice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingWorker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import VASSAL.build.GameModule;
import VASSAL.build.module.DiceButton;
import VASSAL.build.module.DieRoll;
import VASSAL.build.module.InternetDiceButton;
import VASSAL.tools.ErrorDialog;
import VASSAL.tools.FormattedString;
import VASSAL.tools.io.IOUtils;


/**
 * Base DieServer Class
 * Does most of the work. Individual Die Servers just need to implement
 * {@link #buildInternetRollString} and {@link #parseInternetRollString}
 * methods.
 */
public abstract class DieServer {
  private static final Logger logger = LoggerFactory.getLogger(DieServer.class);

  protected java.util.Random ran;
  protected String name;
  protected String description;
  protected boolean emailOnly;
  protected int maxRolls;
  protected int maxEmails;
  protected String serverURL;
  protected boolean passwdRequired = false;
  protected String password = "";
  protected boolean useEmail;
  protected String primaryEmail;
  protected String secondaryEmail;
  protected boolean canDoSeparateDice = false;

  /*
   * Each implemented die server must provide this routine to build a
   * string that will be sent to the internet site to drive the web-based
   * die server. This will usually be a control string passed to a cgi script
   * on the site.
   */
  public abstract String[] buildInternetRollString(RollSet mr);

  /*
   * Each implemented die server must provide this routine to interpret the
   * html output generated by the site in response to the
   * {@link #buildInternetRollString} call.
   */
  public abstract void parseInternetRollString(RollSet rollSet, Vector<String> results);

  /*
   * Internet Die Servers should always implement roll by calling back to
   * {@link #doInternetRoll}
   */
  public abstract void roll(RollSet mr, FormattedString format);

  public DieServer() {
    ran = GameModule.getGameModule().getRNG();
  }

  /*
   * Some Internet servers can only roll specific numbers of dice or
   * dice with specific sides. These are the default settings.
   */
  public int[] getnDiceList() {
    return new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
  }

  public int[] getnSideList() {
    return new int[]{2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 20, 30, 50, 100, 1000};
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public boolean isPasswdRequired() {
    return passwdRequired;
  }

  public String getPasswd() {
    return password;
  }

  public void setPasswd(String s) {
    password = s;
  }

  public void setPrimaryEmail(String e) {
    primaryEmail = e;
  }

  public String getPrimaryEmail() {
    return primaryEmail;
  }

  public void setSecondaryEmail(String e) {
    secondaryEmail = e;
  }

  public String getSecondaryEmail() {
    return secondaryEmail;
  }

  public void setUseEmail(boolean use) {
    useEmail = use;
  }

  public boolean getUseEmail() {
    return useEmail;
  }

  public int getMaxEmails() {
    return maxEmails;
  }

  /**
   * The text reported before the results of the roll
   */
  protected String getReportPrefix(String d) {
    return " *** " + d + " = ";
  }

  /**
   * The text reported after the results of the roll;
   * @deprecated
   */
  @Deprecated
  protected String getReportSuffix() {
    return " ***  <" + GameModule.getGameModule().getChatter().getHandle() + ">";
  }

  /*
   * Called by the Inbuilt server - Basically the same as the code
   * in the original DiceButton
   */
  public void doInbuiltRoll(RollSet mroll) {
    DieRoll[] rolls = mroll.getDieRolls();
    for (DieRoll roll : rolls) {
      String desc = roll.getDescription();
      int nSides = roll.getNumSides();
      int nDice = roll.getNumDice();
      int plus = roll.getPlus();
      boolean reportTotal = roll.isReportTotal();

      String val = getReportPrefix(desc);
      int total = 0;
      for (int j = 0; j < nDice; ++j) {
        final int result = ran.nextInt(nSides) + 1 + plus;
        if (reportTotal) {
          total += result;
        }
        else {
          val += result;
          if (j < nDice - 1)
            val += ",";
        }

        if (reportTotal)
          val += total;

        val += getReportSuffix();
        GameModule.getGameModule().getChatter().send(val);
      }
    }
  }

  /*
   * Internet Servers will call this routine to do their dirty work.
   */
  public void doInternetRoll(final RollSet mroll, final FormattedString format) {
    // FIXME: refactor so that doInBackground can return something useful
    new SwingWorker<Void,Void>() {
      @Override
      public Void doInBackground() throws Exception {
        doIRoll(mroll);
        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          reportResult(mroll, format);
        }
        catch (InterruptedException e) {
          ErrorDialog.bug(e);
        }
        // FIXME: review error message
        catch (ExecutionException e) {
          logger.error("", e);

          final String s = "- Internet dice roll attempt " +
                           mroll.getDescription() + " failed.";
          GameModule.getGameModule().getChatter().send(s);
        }
      }
    }.execute();
  }

  /**
   * Use the configured FormattedString to format the result of a roll
   * @param result
   * @return
   */
  protected String formatResult(String description, String result, FormattedString format) {
    format.setProperty(DiceButton.RESULT, result);
    format.setProperty(InternetDiceButton.DETAILS, description);
    final String text = format.getText();
    return text.startsWith("*") ? "*" + text : "* " + text;
  }


  public void reportResult(RollSet mroll, FormattedString format) {
    DieRoll[] rolls = mroll.getDieRolls();
    for (DieRoll roll : rolls) {
      int nDice = roll.getNumDice();
      boolean reportTotal = roll.isReportTotal();

      String val = "";
      int total = 0;

      for (int j = 0; j < nDice; j++) {
        int result = roll.getResult(j);
        if (reportTotal) {
          total += result;
        }
        else {
          val += result;
          if (j < nDice - 1)
            val += ",";
        }
      }

      if (reportTotal)
        val += total;

      val = formatResult(roll.getDescription(), val, format);
      GameModule.getGameModule().getChatter().send(val);
    }
  }

  public void doIRoll(RollSet toss) throws IOException {
    final String[] rollString = buildInternetRollString(toss);
    final ArrayList<String> returnString = new ArrayList<String>();
    //            rollString[0] =
    //                "number1=2&type1=6&number2=2&type2=30&number3=2&type3=30"
    //                    + "&number4=0&type4=2&number5=0&type5=2&number6=0&type6=2&number7=0&type7=2"
    //                    + "&number8=0&type8=2&number9=0&type9=2&number10=0&type10=2"
    //                    + "&emails=&email=b.easton@uws.edu.au&password=IG42506&Submit=Throw+Dice";
    final URL url = new URL(serverURL);

    final URLConnection connection = url.openConnection();
    connection.setDoOutput(true);

    final PrintWriter out = new PrintWriter(connection.getOutputStream());
    try {
      for (String s : rollString) out.println(s);
      out.close();
    }
    finally {
      IOUtils.closeQuietly(out);
    }

    BufferedReader in = null;
    try {
      in = new BufferedReader(
        new InputStreamReader(connection.getInputStream()));

      String inputLine;
      while ((inputLine = in.readLine()) != null) returnString.add(inputLine);

      in.close();
    }
    finally {
      IOUtils.closeQuietly(in);
    }

    parseInternetRollString(toss, new Vector<String>(returnString));
  }

  /**
   *
   * Extract the portion of the email address withing the  angle brackets.
   * Allows Email addresses like 'Joe Blow <j.blow@somewhere.com>'
   */
  public String extractEmail(String email) {
    int start = email.indexOf('<');
    int end = email.indexOf('>');
    if (start >= 0 && end >= 0 && end > start) {
      return email.substring(start + 1, end);
    }
    else {
      return email;
    }
  }
}
