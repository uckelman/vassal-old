/**
 * 
 */
package VASSAL.tools;

import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Read the XML file (vassal.xml) which defines the location of the vassal engine server.
 * 
 * @author tdecarlo
 *
 */
public class ConfigFileReader {
	private static final Logger logger =
			LoggerFactory.getLogger(ConfigFileReader.class);
	
	private Boolean serverSSL = false;
	private String serverName = "127.0.0.1"; //$NON-NLS-1$
	private String serverURL = "http://127.0.0.1"; //$NON-NLS-1$
	private String serverURLAndPort = "http://127.0.0.1:5050"; //$NON-NLS-1$
	private Integer serverPort = 5050;
	private String wiki = "/wiki";
	private String forum = "/forum";
	  
	public ConfigFileReader() {
		
		// read server URL and port from the configuration file
		Configurations configs = new Configurations();
		try
		{
			XMLConfiguration config = configs.xml("vassal.xml"); //$NON-NLS-1$
			
			serverSSL = config.getBoolean("useSSL", false); //$NON-NLS-1$
			serverName = config.getString("server"); //$NON-NLS-1$
			serverPort = config.getInteger("port", 5050); //$NON-NLS-1$
			serverURL = 
					(serverSSL ? "https://" : "http://") 
					+ serverName; //$NON-NLS-1$ //$NON-NLS-2$
			serverURLAndPort = 
					serverURL
					+ ":" + serverPort.toString(); //$NON-NLS-1$
            wiki = serverURL + config.getString("wiki", "/wiki"); //$NON-NLS-1$ //$NON-NLS-2$
            forum = serverURL + config.getString("forum", "/forum"); //$NON-NLS-1$ //$NON-NLS-2$
			
		}
		catch (ConfigurationException cex)
		{
			logger.error("error reading configuration file", cex); //$NON-NLS-1$
		}
	}

	/**
	 * @return the serverSSL
	 */
	public Boolean getServerSSL() {
		return serverSSL;
	}

	/**
	 * @return the serverName
	 */
	public String getServerName() {
		return serverName;
	}

	/**
	 * @return the serverPort
	 */
	public Integer getServerPort() {
		return serverPort;
	}
	
	/**
	 * @return the serverURL, constructed from the SSL state and server name
	 */
	public String getServerURL() {
		return serverURL;
	}

	/**
	 * @return the serverURLAndPort, constructed from the SSL state, server name, and port
	 */
	public String getServerURLAndPort() {
		return serverURLAndPort;
	}

    /**
     * @return the wiki URL
     */
    public String getWiki() {
        return wiki;
    }

    /**
     * @return the forum URL
     */
    public String getForum() {
        return forum;
    }

	
}
