package session;

import groupMembership.GroupMembership;
import groupMembership.Server;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.servlet.http.*;

import rpc.RPCClient;

import com.google.gson.*;

/**
 * Manages sessions in a threadsafe table
 * 
 * @author Harrison
 * 
 */
public class SessionManager {
  protected static final String cookieName = "CS5300SESSION";
  protected static final Integer sessionTimeout = 600; // Timeout time in seconds
                                                      // of session
  protected static final Integer sessionCleanerFrequency = 60; // Delay in
                                                              // seconds for
                                                              // running cleaner

  // Locks for session table
  protected static final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
  protected static final Lock readlock = rwl.readLock();
  protected static final Lock writelock = rwl.writeLock();
  protected static final Hashtable<String, Session> sessions = new Hashtable<String, Session>();

  // Sweeper for session cleanup
  protected static final SessionCleaner sessionCleaner = new SessionCleaner();
  protected static final Timer sessionCleanerTimer = new Timer();

  /**
   * Retrieve a session from the table or create one Increment the version of
   * the session Save this back as a cookie
   */
  public static Session getAndIncrement(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    Cookie cookie = null;
    Session session;
    if (cookies != null) {
      for (int i = 0; i < cookies.length; i++) {
        Cookie c = cookies[i];
        if (c.getName().equals(cookieName)) {
          cookie = c;
        }
      }
    }
    if (cookie == null) {
      session = initialize();
    } else {
      session = getSessionFromCookie(cookie.getValue());
      // If we are unable to get session
      if (session == null) {
        session = initialize();
      } else {
        session.incrementVersion();
      }
    }
    return session;
  }
  
  public static void putCookie(HttpServletResponse response, Session session) {
    Cookie cookie = new Cookie(cookieName, makeCookie(session));
    cookie.setMaxAge(sessionTimeout);
    response.addCookie(cookie);
  }

  /**
   * Destroy a session by expiring the cookie
   */
  public static void destroy(HttpServletRequest request,
      HttpServletResponse response, Session session) {
    Cookie[] cookies = request.getCookies();
    Cookie cookie = null;
    if (cookies != null) {
      for (int i = 0; i < cookies.length; i++) {
        Cookie c = cookies[i];
        if (c.getName().equals(cookieName)) {
          cookie = c;
        }
      }
    }
    if (cookie != null) {
      // Expire the cookie
      cookie.setMaxAge(0);
      response.addCookie(cookie);
    }
    /*
    writelock.lock();
    try {
      sessions.remove(session.getSessionID());
    } finally {
      writelock.unlock();
    }
    */
  }

  /**
   * Start session cleaner
   */
  public static void startCleaner() {
    sessionCleanerTimer.schedule(sessionCleaner,
        sessionCleanerFrequency * 1000, sessionCleanerFrequency * 1000);
  }

  /**
   * Cleanup loose threads Call this when stopping sessions
   */
  public static void cleanup() {
    sessionCleanerTimer.cancel();
  }

  /**
   * Create a new session
   */
  private static Session initialize() {
    String uuid = UUID.randomUUID().toString();
    Session s = new Session(uuid, GroupMembership.getServers());
    /*
    writelock.lock();
    try {
      sessions.put(uuid, s);
    } finally {
      writelock.unlock();
    }*/
    return s;
  }

  /**
   * Create a cookie string
   */
  private static String makeCookie(Session s) {
    Gson gson = new Gson();
    String[] cookie_params = { s.getSessionID(), s.getVersion(),
        s.getLocationsString() };
    try {
      return URLEncoder.encode(gson.toJson(cookie_params), "UTF-8");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Find session from a given cookie string Returns null if unable to be found
   * or version numbers are incorrect
   */
  private static Session getSessionFromCookie(String cookie) {
    Gson gson = new Gson();
    String[] cookie_params;
    try {
      cookie_params = gson.fromJson(URLDecoder.decode(cookie, "UTF-8"),
          String[].class);
      if ((cookie_params == null) || (cookie_params.length < 3)) {
        return null;
      } else {
        String[] locs = cookie_params[2].split(",");
        List<Server> servers = new ArrayList<Server>();
        for(String l : locs) {
          String[] parts = l.split(":");
          servers.add(new Server(parts[0],parts[1]));
        }
        Session s = SessionManager.getSessionById(cookie_params[0], cookie_params[1]);
        if (s != null){
          s.setLocations(servers);
          
          return s;
        }
        s = new Session(cookie_params[0], servers);
        s.setVersion(Integer.valueOf(cookie_params[1]));
        Session session = RPCClient.get(s);
        if (session == null) {
          return null;
        } else {
          // Check to see if the versions match
          if (session.getVersion().equals(cookie_params[1])) {
            return session;
          } else {
            return null;
          }
        }
        /*
        readlock.lock();
        try {
          Session session = sessions.get(cookie_params[0]);
          if (session == null) {
            return null;
          } else {
            // Check to see if the versions match
            if (session.getVersion().equals(cookie_params[1])) {
              return session;
            } else {
              return null;
            }
          }
        } finally {
          readlock.unlock();
        }
        */
      }
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Used in SSM Brick
   * 
   * @param sessionID
   * @param version
   * @return
   */
  public static Session getSessionById(String sessionID, String version) {
    System.out.println("Server trying to retrieve: " + sessionID + "," + version);
    readlock.lock();
    try {
      Session session = sessions.get(sessionID);
      if (session == null) {
        System.out.println("server doesn't have session");
        return null;
      } else {
        // Check to see if the versions match
        if (session.getVersion().equals(version)) {
          return session;
        } else {
          System.out.println(session.getVersion() + " does not match with " + version);
          return null;
        }
      }
    } finally {
      readlock.unlock();
    }
  }

  /**
   * Used in SSM Brick
   * 
   * @param sessionid
   * @param version
   * @param count
   * @param message
   */
  public static void putSession(String sessionid, String version, String count,
      String message) {
    System.out.println("server adding session: " + sessionid +"," + version);
    writelock.lock();
    Session session = sessions.get(sessionid);
    if (session == null) {
      session = new Session(sessionid, null);
      sessions.put(sessionid, session);
    }
    session.setData("count", count);
    session.setVersion(Integer.valueOf(version));
    session.setData("message", message);
    session.updateTimestamp();
    writelock.unlock();
  }
}
